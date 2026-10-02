#!/usr/bin/env bash
# =============================================================================
# Deploy without taking the site down.
#
# The app takes 20-30 seconds to boot. Recreating the only container means the
# site answers 502 for that whole window, so instead there are two identical
# containers behind Caddy (app-blue and app-green, see docker-compose.prod.yml)
# and this script restarts them ONE AT A TIME, waiting for each to report
# healthy before touching the other. There is always one serving, and Caddy's
# health checks route around whichever is restarting.
#
# Run it on the server, from the directory holding docker-compose.prod.yml:
#
#     ./deploy.sh
#
# If the new build is broken it stops after the first container and says so.
# The other is still running the previous image, so the site stays up on the
# old version rather than going down on the new one — the failure mode worth
# designing for.
#
# ---- One thing this does NOT solve -----------------------------------------
#
# For about a minute, the old and new versions run at the same time against one
# database, and the new container runs its Flyway migrations before the old one
# has gone. So a migration must not break the version already running: add
# columns, do not drop or rename them in the same deploy. Dropping a column the
# old code still SELECTs takes the site down in the one way this script cannot
# help with. Split it across two deploys — stop using it, ship, then drop it.
# =============================================================================

set -euo pipefail

COMPOSE_FILE="docker-compose.prod.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"

# Green first, then blue. The order does not matter in itself — what matters is
# that it is one at a time and that each is healthy before the next.
CONTAINERS=(app-green app-blue)

# Longest a container gets to come up before this is called a failed deploy.
# The healthcheck in the compose file polls every 5s; this is the outer bound,
# generous enough for a boot that also applies a slow migration.
BOOT_TIMEOUT=240

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31m!!! %s\033[0m\n' "$*" >&2; exit 1; }

# ---- Checks worth doing before anything is changed --------------------------
# All of these are cheap, and every one of them is a way a deploy has failed
# halfway through with one container already restarted.

[ -f "$COMPOSE_FILE" ] || fail "No $COMPOSE_FILE here. Run this from the deploy directory."
[ -f nt.env ]          || fail "No nt.env here — the app would start without its secrets."
[ -f Caddyfile ]       || fail "No Caddyfile here. Copy Caddyfile.example and set your domain."

# The Caddyfile has to name the two upstreams, or Caddy is still proxying to a
# service called `app` that no longer exists and every request is a 502 the
# moment the old container goes. Checked here, before anything is touched,
# because the symptom otherwise appears halfway through a deploy.
grep -q 'app-blue' Caddyfile || fail "Caddyfile still points at the old single 'app' upstream.
Update it from Caddyfile.example — the reverse_proxy line needs both app-blue
and app-green, with the health checks — then run this again."

# ---- Build once -------------------------------------------------------------
# Only app-blue declares `build:` in the compose file; it produces the
# nexttenant:latest tag that BOTH containers run. Building before anything is
# restarted means a compile error costs nothing: the site has not been touched.
say "Building the image"
$COMPOSE build app-blue

wait_until_healthy() {
    local service="$1"
    local waited=0
    local id status

    id="$($COMPOSE ps -q "$service")"
    [ -n "$id" ] || fail "$service did not start at all. Check: $COMPOSE logs $service"

    while [ "$waited" -lt "$BOOT_TIMEOUT" ]; do
        # Docker's own verdict, from the healthcheck in the compose file, rather
        # than this script sleeping a fixed number of seconds and hoping. A
        # fixed sleep is wrong in both directions: too short on a slow boot, and
        # wasted downtime-risk window on a fast one.
        status="$(docker inspect --format '{{.State.Health.Status}}' "$id" 2>/dev/null || echo missing)"

        case "$status" in
            healthy)
                printf '\n'
                return 0
                ;;
            missing)
                fail "$service disappeared while starting. Check: $COMPOSE logs $service"
                ;;
            unhealthy)
                # Docker says it has stopped retrying. Nothing is gained by
                # waiting out the rest of the timeout.
                fail "$service came up unhealthy. The other container is still serving the previous version, so the site is up. Check: $COMPOSE logs $service"
                ;;
        esac

        printf '.'
        sleep 3
        waited=$((waited + 3))
    done

    fail "$service was not healthy after ${BOOT_TIMEOUT}s. The other container is still serving the previous version. Check: $COMPOSE logs $service"
}

# ---- One-time: retire the single-container stack ----------------------------
#
# Before blue/green there was one app service, called `app`, bound to
# 127.0.0.1:8080. It is not in this compose file any more, so `docker compose`
# leaves it running as an orphan — and it still holds port 8080, which is the
# port app-blue wants. The first deploy after the upgrade therefore dies with
#
#     Bind for 127.0.0.1:8080 failed: port is already allocated
#
# halfway through, which is the worst moment to meet it.
#
# Removed between green and blue, not before either: green binds 8081, so it
# can come up and start taking traffic while the old container is still
# serving. Only once green is healthy is the old one dropped and 8080 freed.
# That makes the upgrade itself zero-downtime rather than a cutover.
#
# This whole block can be deleted once every environment has deployed past it.
retire_single_container_stack() {
    local old
    old="$(docker ps -aq \
            --filter "label=com.docker.compose.project=$(basename "$PWD")" \
            --filter "label=com.docker.compose.service=app" 2>/dev/null || true)"

    [ -n "$old" ] || return 0

    say "Removing the old single-container 'app' (pre-blue/green), which still holds port 8080"
    docker rm -f $old > /dev/null
}

for service in "${CONTAINERS[@]}"; do
    # app-green is first in CONTAINERS and binds 8081, so it is already healthy
    # and serving by the time the old container is dropped.
    if [ "$service" = "app-blue" ]; then
        retire_single_container_stack
    fi

    say "Restarting $service on the new image"

    # --force-recreate because the image tag has not changed. Without it Docker
    # sees the same `nexttenant:latest` in the compose file, concludes there is
    # nothing to do, and the deploy silently keeps running the old code — which
    # looks exactly like a successful deploy until somebody notices the fix is
    # not live.
    #
    # --no-deps so recreating an app container does not also bounce Caddy, which
    # would drop every connection and defeat the entire point of this script.
    $COMPOSE up -d --force-recreate --no-deps "$service"

    printf '    waiting for %s to report healthy ' "$service"
    wait_until_healthy "$service"
    say "$service is healthy"
done

# Caddy only needs recreating if its own config changed, and recreating it is
# the one thing here that IS briefly visible to users. Left out on purpose —
# after editing the Caddyfile, reload it in place with no dropped connections:
#
#     docker compose -f docker-compose.prod.yml exec caddy caddy reload --config /etc/caddy/Caddyfile

say "Deployed. Both containers are on the new image."
$COMPOSE ps
