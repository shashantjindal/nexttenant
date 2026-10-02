# NextTenant — day-to-day commands

Everything here runs **on the server**, as `ec2-user`, from `~/nexttenant`.

**Getting there:** EC2 → Instances → select `nexttenant` → **Connect** → **SSM
Session Manager** → Connect. Then:

```bash
sudo su - ec2-user
cd ~/nexttenant
```

---

## Make the long command short

Do this once and every command below loses eleven words:

```bash
echo "alias ntc='docker compose -f ~/nexttenant/docker-compose.prod.yml'" >> ~/.bashrc
source ~/.bashrc
```

Then `ntc ps`, `ntc logs -f app-blue app-green`, and so on, from any directory.

> **`source` is not optional** — without it the alias exists only in shells
> started afterwards, so it appears not to have worked.
>
> Check with `type ntc`; it should print the alias rather than "not found".
>
> And do not call it `dc`: that is already the GNU desk calculator, so the
> mistake surfaces as `dc: invalid option -- 'd'`, which looks like Docker
> complaining and is not.

---

## "port is already allocated"

```
Bind for 127.0.0.1:8080 failed: port is already allocated
```

Something else already holds 8080. Two causes, both ordinary:

**On your own machine** — `./gradlew bootRun` is running. The development
server and `app-blue` both want 8080. Stop the dev server, or move the
containers out of its way:

```bash
NT_BLUE_PORT=9080 NT_GREEN_PORT=9081 docker compose -f docker-compose.prod.yml up -d
```

Those two ports are a diagnostic convenience only — Caddy reaches the
containers over Docker's private network and does not care what they are.

**On the server, the first time after the blue/green upgrade** — the old
single `app` container is still running and still holding 8080. `docker compose`
leaves it alone because it is no longer in the compose file. `./deploy.sh`
removes it for you, at the right moment: after `app-green` is up and serving on
8081, so nothing is down in between. If you hit this by running `up -d` by hand
instead, let the script do it:

```bash
./deploy.sh
```

---

## There are two app containers

`app-blue` and `app-green`, identical, both taking traffic, with Caddy
health-checking each one and routing only to the ones answering. They exist so
that a deploy can restart them one at a time: the app takes 20–30 seconds to
boot, and with a single container that was 20–30 seconds of 502s on every
deploy.

So most commands below name both. The two that must name **one**:

```bash
ntc exec app-blue printenv | grep NEXTTENANT   # exec runs in one container
ntc logs app-green --tail 50                   # when only one is misbehaving
```

They are interchangeable because the app keeps nothing on its own disk —
sessions are in Postgres, photos in S3. If you ever set
`NEXTTENANT_PHOTO_STORAGE=local`, half the photo requests will 404 on the
container that does not have the file.

---

## The one distinction that matters

These three look interchangeable and are not. Picking the wrong one is how a
change appears to have no effect:

| Command | What it does | Picks up |
|---|---|---|
| `restart app-blue app-green` | re-runs the process in the **same containers** | nothing |
| `up -d --force-recreate app-blue app-green` | builds **new containers** from current config | **`nt.env` changes** |
| `./deploy.sh` | rebuilds the **image**, then rolls one container at a time | **code changes**, with no downtime |

Environment variables are fixed when a container is created. So editing
`nt.env` and running `restart` changes nothing at all, silently — the old
values are still inside the running container. Check what one actually has:

```bash
ntc exec app-blue printenv | grep NEXTTENANT
```

---

## What changed → what to run

### Code (you pushed a commit)

```bash
cd ~/nexttenant
git pull
./deploy.sh
```

3–8 minutes, and **the site stays up throughout**. The script builds the image
once, then restarts `app-green`, waits for it to report healthy, and only then
restarts `app-blue` — so one container is always serving. If the new build is
broken it stops after the first and says so, leaving the other on the previous
version.

Database migrations run automatically on startup. Because both versions are
briefly live at once, a migration must not break the version already running:
**add** columns, never drop or rename one the deployed code still reads. Split
that into two deploys — stop using the column, ship, then drop it.

`ntc up -d --build` still works and is still 20–30 seconds of 502s. Use
`./deploy.sh`.

### `nt.env` (credentials, mail provider, any setting)

```bash
nano nt.env
ntc up -d --force-recreate --no-deps app-green   # wait for it in `ntc ps`
ntc up -d --force-recreate --no-deps app-blue
```

No rebuild — only the environment changed. One at a time, for the same reason
deploys are: recreating both together is downtime. `--no-deps` keeps Caddy out
of it, since bouncing the proxy drops every connection and defeats the point.

### `Caddyfile` (domains, redirects)

```bash
nano Caddyfile
ntc exec caddy caddy reload --config /etc/caddy/Caddyfile
```

A reload rather than `restart caddy`: it swaps the configuration in place
without dropping a single connection, where a restart is a visible blip for
everyone currently loading a page. Caddy requests any new certificates by
itself either way.

### Nothing — you just want a clean restart

```bash
ntc restart app-green     # wait for `healthy` in `ntc ps`, then
ntc restart app-blue
```

One at a time and no visitor sees anything: Caddy keeps serving from the other
container. Restarting both at once is about 20 seconds of 502s — that is Caddy
having nothing to proxy to yet, not a fault, but there is no reason to do it.

---

## Looking at things

```bash
ntc ps                             # what is running, and is it healthy
ntc logs -f app-blue app-green     # follow live (Ctrl+C stops watching, not the app)
ntc logs app-blue --tail 60        # last 60 lines from one container
ntc logs caddy --tail 30           # certificates, TLS, proxy errors
```

`ps` shows a health column for both app containers. `healthy` there means the
same thing the proxy means by it: the app answered
`/actuator/health/readiness`, which includes the database answering.

**Is it actually up?**

```bash
curl http://localhost:8080/actuator/health    # app-blue
curl http://localhost:8081/actuator/health    # app-green
```

`{"status":"UP"}` — and `UP` means the **database answered too**, not merely
that the JVM is running. One command that proves RDS, the security group,
PostGIS and every migration.

Both ports are bound to loopback, so they answer only from the server itself.
That is deliberate: published on every interface they would serve the whole
site over plain HTTP, certificate bypassed.

**Find an error:**

```bash
ntc logs app-blue app-green --tail 300 | grep -A3 "Caused by" | head -30
```

`Caused by` walks to the root of the stack trace. Grepping for `error` alone
usually returns the least useful line.

---

## Sign-in codes while mail is on `console`

```bash
ntc logs app-blue app-green | grep -A8 "EMAIL" | tail -15
```

The six-digit code is in the subject line and the body.

---

## The database

```bash
psql -h <rds-endpoint> -U postgres -d nexttenant
```

Endpoint: RDS → Databases → `nexttenant-db` → Connectivity & security.

```sql
SELECT count(*) FROM listings;                  -- expect 0 until people list
SELECT count(*) FROM users;
SELECT status, count(*) FROM interest_requests GROUP BY status;
\dt                                             -- list tables
\q
```

**Make someone an admin:**

```sql
UPDATE users SET is_admin = true
 WHERE id = (SELECT user_id FROM user_private WHERE email = 'you@example.com');
```

---

## When something is wrong

**502 in the browser** — the app is not answering.

```bash
ntc ps
```

`Restarting` on a loop means it is crashing, nearly always a bad value in
`nt.env`. The reason is in `ntc logs app-blue app-green --tail 50`.

**Out of disk:**

```bash
df -h
docker system prune -a       # removes unused images and build cache
```

**Out of memory** — check swap is still on:

```bash
free -h
```

Swap should read 2.0Gi. If it is 0, the `/etc/fstab` line is missing and a
reboot dropped it.

**Start over cleanly:**

```bash
ntc down
ntc up -d --build
```

This one **is** downtime — everything stops and both app containers boot at
once. It is the right tool when something is genuinely wedged and the wrong one
for a normal deploy, which is `./deploy.sh`.

`down` stops and removes the containers. It does **not** touch the database
(that is RDS) or the photos (those are S3) or Caddy's certificates (a named
volume). Nothing is lost.

---

## Occasionally

**Update the server's packages:**

```bash
sudo dnf update -y
```

**Reboot** — EC2 → Instance state → Reboot. The site comes back by itself:
Docker starts on boot and the containers are `restart: unless-stopped`. Worth
proving once, deliberately, rather than discovering it during an incident.

**Session dropped mid-build?** Reconnect, `sudo su - ec2-user`, then
`tmux attach` — you are back in the same shell with the build still running.
Start long jobs inside `tmux` for exactly this reason.

---

## Watch the money

**Billing → Cost Explorer**, grouped by service, once a week. Four things
quietly eat a budget:

- RDS **storage autoscaling** — should be off
- **Old snapshots** — kept and billed forever
- **Unattached Elastic IPs** — free while attached, charged when not
- **CloudWatch log groups** — default to never expiring
