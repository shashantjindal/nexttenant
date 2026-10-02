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
#
# The project name is asked of Docker rather than guessed from the directory.
# This compose file pins no `name:`, so Compose derives it from the folder — and
# a folder renamed at any point in the past means a guess here silently matches
# nothing, the orphan survives, and the deploy dies on the port bind with no
# clue why. Reading the label off a container Compose itself started is the
# answer Compose would give.
retire_single_container_stack() {
    local sibling project old

    sibling="$($COMPOSE ps -aq 2>/dev/null | head -1)"
    if [ -n "$sibling" ]; then
        project="$(docker inspect -f \
                '{{index .Config.Labels "com.docker.compose.project"}}' "$sibling" 2>/dev/null)"
    fi
    # Nothing from this project is running yet — a first deploy, where there is
    # no old container to retire either. The directory name is what Compose
    # would use, so it is the right fallback.
    project="${project:-$(basename "$PWD")}"

    old="$(docker ps -aq \
            --filter "label=com.docker.compose.project=$project" \
            --filter "label=com.docker.compose.service=app" 2>/dev/null || true)"

    [ -n "$old" ] || return 0

    say "Removing the old single-container 'app' (pre-blue/green), which still holds port 8080"
    docker rm -f $old > /dev/null
}

#
# Fetches the real site through Caddy, over TLS, exactly as a visitor would —
# and resolves the domain to this machine rather than trusting DNS, so the check
# cannot accidentally pass against something else.
#
# The domain is read from the Caddyfile rather than hardcoded, because that file
# is where it is already written down and a second copy is a second thing to get
# wrong.
#
# Worth being clear about what this does and does not catch: it proves the proxy
# can reach an app container and get a page back. It does not prove the page is
# correct. That is still a far better signal than every check in this script
# combined, because the failure it catches — healthy containers, dead site — is
# the one that already happened once and reported itself as a success.
verify_site_responds() {
    local when="$1" domain code

    domain="$(awk '/^[A-Za-z0-9][A-Za-z0-9.-]*[[:space:]]*\{/ {print $1; exit}' Caddyfile)"
    if [ -z "$domain" ]; then
        printf '    (no site address found in Caddyfile; skipping the end-to-end check)\n'
        return 0
    fi

    # -k because this is dialling 127.0.0.1 with the real hostname: the
    # certificate is valid for the domain, and whether it matches the loopback
    # address is not what is being tested here.
    code="$(curl -sk -o /dev/null -w '%{http_code}' --max-time 15 \
            --resolve "$domain:443:127.0.0.1" "https://$domain/" 2>/dev/null || echo 000)"

    case "$code" in
        2*|3*)
            printf '    %s responds %s %s\n' "$domain" "$code" "$when"
            ;;
        *)
            # ---- Keep the evidence --------------------------------------
            #
            # Written to a file BEFORE anything else, because the first
            # instinct when the site is down is to recreate Caddy — and that
            # works, and it throws away the only record of why it was needed.
            # That is exactly how the cause of the outage during the blue/green
            # migration was lost: by the time anybody thought to look, the
            # container holding the logs had been replaced.
            #
            # Cheap to write, and the one artefact worth having.
            local evidence="deploy-failure-$(date -u +%Y%m%dT%H%M%SZ).log"
            {
                echo "=== $domain returned $code $when"
                echo
                echo "=== docker ps"
                docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}' 2>&1
                echo
                echo "=== caddy logs"
                $COMPOSE logs caddy --tail 200 --no-color 2>&1
                echo
                echo "=== can caddy reach the containers by name"
                for upstream in app-blue app-green; do
                    printf '%s: ' "$upstream"
                    docker exec "$($COMPOSE ps -q caddy)" \
                            wget -qO- --timeout=3 \
                            "http://$upstream:8080/actuator/health/readiness" 2>&1 \
                        || echo "(unreachable)"
                    echo
                done
                echo "=== networks"
                docker inspect -f '{{.Name}}{{range $k,$v := .NetworkSettings.Networks}} -> {{$k}} ({{$v.NetworkID}}){{end}}' \
                        $($COMPOSE ps -q) 2>&1
            } > "$evidence" 2>&1

            fail "$domain returned $code $when, although the containers report healthy.
That combination means the proxy is not reaching them: the containers are fine
and the site is not.

Everything needed to diagnose it is in ./$evidence — READ IT, or send it on,
BEFORE touching anything. Recreating Caddy usually clears this and destroys the
evidence with it, which is how the last occurrence went unexplained.

The site may still be up: only $service was replaced, and the other container
was left alone deliberately. Check before doing anything drastic.

When you need it back more than you need the cause:
  $COMPOSE up -d --force-recreate caddy"
            ;;
    esac
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

    # ---- Nudge Caddy, then PROVE the site still works ------------------------
    #
    # The reload is cheap insurance, not a diagnosis. A recreated container gets
    # a new IP on the Docker network, and re-reading the config makes Caddy
    # resolve the upstream names again. It swaps configuration in place without
    # dropping a connection, so it costs nothing and cannot make things worse.
    #
    # Be honest about what is NOT known: during the blue/green migration the
    # site returned 502 with both containers healthy and the config valid, and a
    # reload did NOT fix it — recreating Caddy did. So stale addresses are a
    # plausible contributor and are demonstrably not the whole story. Until the
    # cause is pinned down, this script does not rely on reasoning about it.
    #
    # Which is what the check below is for. Everything else here measures the
    # containers; this measures the thing a visitor actually gets. A deploy that
    # leaves every container healthy and every request failing is the exact
    # shape of the outage above, and `docker ps` reports it as success.
    say "Reloading Caddy so it re-resolves $service"
    $COMPOSE exec -T caddy caddy reload --config /etc/caddy/Caddyfile \
        || fail "Caddy would not reload. The other container is still serving.
Check: $COMPOSE logs caddy --tail 40"

    verify_site_responds "after restarting $service"
done

say "Deployed. Both containers are on the new image."
$COMPOSE ps
