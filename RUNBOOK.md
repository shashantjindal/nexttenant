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

Then `ntc ps`, `ntc logs -f app`, and so on, from any directory.

> **`source` is not optional** — without it the alias exists only in shells
> started afterwards, so it appears not to have worked.
>
> Check with `type ntc`; it should print the alias rather than "not found".
>
> And do not call it `dc`: that is already the GNU desk calculator, so the
> mistake surfaces as `dc: invalid option -- 'd'`, which looks like Docker
> complaining and is not.

---

## The one distinction that matters

These three look interchangeable and are not. Picking the wrong one is how a
change appears to have no effect:

| Command | What it does | Picks up |
|---|---|---|
| `restart app` | re-runs the process in the **same container** | nothing |
| `up -d --force-recreate app` | builds a **new container** from current config | **`nt.env` changes** |
| `up -d --build` | rebuilds the **image** from source first | **code changes** |

Environment variables are fixed when a container is created. So editing
`nt.env` and running `restart` changes nothing at all, silently — the old
values are still inside the running container. Check what it actually has:

```bash
ntc exec app printenv | grep NEXTTENANT
```

---

## What changed → what to run

### Code (you pushed a commit)

```bash
cd ~/nexttenant
git pull
ntc up -d --build
```

3–8 minutes. Database migrations run automatically on startup.

### `nt.env` (credentials, mail provider, any setting)

```bash
nano nt.env
ntc up -d --force-recreate app
```

No rebuild — only the environment changed.

### `Caddyfile` (domains, redirects)

```bash
nano Caddyfile
ntc restart caddy
```

Caddy requests any new certificates by itself.

### Nothing — you just want a clean restart

```bash
ntc restart app
```

About 20 seconds of downtime. Visitors get a 502 while it boots; that is
Caddy having nothing to proxy to yet, not a fault.

---

## Looking at things

```bash
ntc ps                      # what is running, and is it healthy
ntc logs -f app             # follow live  (Ctrl+C stops watching, not the app)
ntc logs app --tail 60      # last 60 lines
ntc logs caddy --tail 30    # certificates, TLS, proxy errors
```

**Is it actually up?**

```bash
curl http://localhost:8080/actuator/health
```

`{"status":"UP"}` — and `UP` means the **database answered too**, not merely
that the JVM is running. One command that proves RDS, the security group,
PostGIS and every migration.

**Find an error:**

```bash
ntc logs app --tail 300 | grep -A3 "Caused by" | head -30
```

`Caused by` walks to the root of the stack trace. Grepping for `error` alone
usually returns the least useful line.

---

## Sign-in codes while mail is on `console`

```bash
ntc logs app | grep -A8 "EMAIL" | tail -15
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
`nt.env`. The reason is in `ntc logs app --tail 50`.

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
