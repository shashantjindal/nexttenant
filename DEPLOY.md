# Deploying NextTenant

A runbook, in the order you should actually do it. Work top to bottom; each
part assumes the ones above it are done.

Prices are indicative and change — treat them as "roughly what to budget", not
quotes. Everything is sized for launch, not for scale.

---

## 0. What you are deploying

Four moving parts:

| Part | What runs it | Why it cannot just live on the app server |
|---|---|---|
| The app | One container | — |
| PostgreSQL 16 + **PostGIS** | Managed database | Backups, point-in-time recovery |
| Photos | S3-compatible bucket | A container's disk is wiped on every deploy |
| Email | SMTP provider | Mail from a random IP goes straight to spam |

PostGIS is not optional. The search is built on `ST_DWithin` over a
`geography` column, and the app will not start without the extension.

---

## 1. What to buy

### The short list

| Thing | Recommendation | Roughly |
|---|---|---|
| Domain | `.in` or `.com` from Cloudflare / Namecheap | ₹900–1,400/yr |
| App server | DigitalOcean droplet, **Bangalore (BLR1)**, 2GB | ~₹1,000/mo |
| Database | DO Managed Postgres, BLR1, 1GB | ~₹1,300/mo |
| Photo storage | Cloudflare R2 | ~₹0 to start |
| Email | Resend or Amazon SES | ₹0 to start |
| Maps | Optional — see §4 | ₹0 to start |

**About ₹2,500–3,000/month** plus the domain.

### Why Bangalore and not the cheapest option

Every user is in Mumbai. Hetzner is half the price and sits in Germany —
roughly 150ms of round trip on every single request, on a server-rendered site
where each click is a round trip. DigitalOcean BLR1 and AWS `ap-south-1`
(Mumbai) are the two realistic homes. Fly.io also has a Mumbai region if you
prefer that model.

### The one decision that actually matters

**Managed database, or Postgres on the same droplet?**

Self-hosting saves about ₹1,300/month and hands you backups, upgrades,
failover and disk monitoring. The failure it invites is losing the database
with no recent dump, and that is not a recoverable mistake — the listings,
the accounts and the connection history are all in there.

Take the managed one. If you self-host anyway, set up `pg_dump` to object
storage on a cron **before** you take a single real signup, and restore from it
once to prove the backup actually works.

### What you do NOT need yet

- **SMS / DLT.** Login is by email, so nothing is blocked on it. See §9.
- **A company.** Nothing above requires one. DLT later does.
- **A CDN.** Photos are served through the app. Add Cloudflare in front when
  it becomes slow, not before.

---

## 2. Turn the dummies into real things

Everything below is already written and sits behind an interface. These are
configuration changes, not code.

### 2.1 Email — `ConsoleEmailSender` → `SmtpEmailSender`

Today, codes and notifications are printed to the log. Nothing reaches anyone.

**Pick a provider.** Resend is the fastest to get working (3,000 emails/month
free). Amazon SES is cheaper at volume (~₹8 per 1,000) and has `ap-south-1`,
but starts in a sandbox that only sends to addresses you have verified — you
must request production access, which takes a day or two. **Do that request
now if you pick SES**, because it is the one thing here with a waiting period.

**Verify your domain**, then add the DNS records the provider gives you:

- **SPF** — says which servers may send as your domain
- **DKIM** — signs each message so it cannot be forged
- **DMARC** — tells inboxes what to do when the other two fail

Skip these and your sign-in codes land in spam. Not "might" — Gmail rejects
unauthenticated bulk mail outright.

```bash
NEXTTENANT_MAIL_PROVIDER=smtp
NEXTTENANT_MAIL_FROM="NextTenant <noreply@yourdomain.in>"
NEXTTENANT_MAIL_REPLY_TO=hello@yourdomain.in
SMTP_HOST=smtp.resend.com
SMTP_PORT=587
SMTP_USERNAME=resend
SMTP_PASSWORD=<api key>
```

`NEXTTENANT_MAIL_FROM` must be an address on the domain you verified. An
unverified sender is rejected by SES and quietly spam-foldered by everyone
else — the second being much worse, because nothing looks wrong.

### 2.2 Photos — `LocalPhotoStorage` → `S3PhotoStorage`

Photos currently write to `./data/photos`, next to the running process. In a
container that directory is destroyed on every deploy. Losing a listing's
photos is losing the listing.

Any S3-compatible provider works — the same class covers Amazon S3, Cloudflare
R2, DigitalOcean Spaces and Backblaze B2. **R2 is the best value**: no egress
charges, and egress is what makes image hosting expensive.

Create a bucket. Keep it **private** — the app serves photos through
`/photos/**` so that EXIF stripping, moderation and takedowns stay meaningful.
A public bucket URL is a permanent link nobody can retract.

```bash
NEXTTENANT_PHOTO_STORAGE=s3
NEXTTENANT_S3_BUCKET=nexttenant-photos
NEXTTENANT_S3_ENDPOINT=https://<account>.r2.cloudflarestorage.com
NEXTTENANT_S3_REGION=auto
NEXTTENANT_S3_ACCESS_KEY=...
NEXTTENANT_S3_SECRET_KEY=...
```

For Amazon S3 itself, leave `ENDPOINT` blank and set `REGION=ap-south-1`.

### 2.3 The OTP secret

`OtpSecretCheck` **refuses to start** outside the dev profile if this is still
the committed default. That is deliberate: a secret in version control is not a
secret, and every login code would be forgeable by anyone who read the repo.

```bash
NEXTTENANT_OTP_SECRET=$(openssl rand -base64 48)
```

Store it wherever your host keeps secrets. If you lose it, every in-flight
code stops working — an hour of annoyance, not a disaster.

### 2.4 The base URL

Emails contain absolute links. A browser knows where a relative path came from;
an inbox does not, so `/requests` in an email is a dead link.

```bash
NEXTTENANT_BASE_URL=https://yourdomain.in
```

Get this wrong and every notification links to `localhost`.

### 2.5 The database

```bash
DB_URL=jdbc:postgresql://<host>:<port>/nexttenant?sslmode=require
DB_USERNAME=...
DB_PASSWORD=...
```

`sslmode=require` matters — a managed database is reached across a network you
do not control.

**Enable PostGIS before first boot**, or Flyway's V1 migration fails:

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
```

### 2.6 The profile

```bash
SPRING_PROFILES_ACTIVE=prod
```

This one line does four things: stops `DevDataSeeder` creating 315 fake
listings, turns off error messages that leak internals, drops logging from
DEBUG to INFO, and makes the app trust `X-Forwarded-*` so it knows it is on
HTTPS behind your proxy.

---

## 3. Deploy

### 3.1 Build

```bash
docker build -t nexttenant:latest .
```

The `Dockerfile` is two-stage — the shipped image has a JRE and one jar, not a
compiler and your source code.

### 3.2 Run

```bash
docker run -d --name nexttenant \
  --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  --env-file /etc/nexttenant.env \
  nexttenant:latest
```

Bound to `127.0.0.1`, not `0.0.0.0` — the app should be reachable only through
the reverse proxy, never directly. Otherwise anyone can skip your TLS by
hitting port 8080.

### 3.3 HTTPS

Caddy is the least work: it obtains and renews certificates by itself.

```
yourdomain.in {
    reverse_proxy 127.0.0.1:8080
}
```

That is the whole config. nginx + certbot works too and is more fiddly.

### 3.4 Health check

`GET /actuator/health` returns `UP` only when the **database answers too**. A
check that reports a healthy JVM in front of a dead database keeps a broken
instance serving traffic. Point your load balancer or monitor at it.

Only `/health` is exposed. The other actuator endpoints publish environment
variables and configuration, which is how a deployment leaks its own database
URL.

### 3.5 Migrations

Flyway runs on startup. Nothing to do — but note `baseline-on-migrate: false`,
so it refuses to run against a non-empty database with no history table. That
is a guard, not a bug.

### 3.6 Deploying again, without the site going down

The app takes 20–30 seconds to boot. Replace the only running copy and that is
20–30 seconds of **502s**, not a slow page — an error page, for everyone who
arrives in that window.

`docker-compose.prod.yml` therefore runs two identical app containers,
`app-blue` and `app-green`, with Caddy health-checking both and sending traffic
only to the ones answering. `./deploy.sh` builds the image once and then
restarts them one at a time, waiting for each to report healthy before touching
the other, so one is always serving:

```bash
git pull
./deploy.sh
```

If the new build fails to come up, the script stops after the first container
and says so. The other is still serving the previous version, so a bad deploy
is "the fix is not live yet" rather than an outage.

Three things make this work, and all three are easy to lose:

- **The app keeps nothing on its own disk.** Sessions are in Postgres
  (`spring-session-jdbc`) and photos in S3. With
  `NEXTTENANT_PHOTO_STORAGE=local` the two containers have different photos and
  roughly half of all photo requests 404. Section 2.2 is not optional once
  there are two containers.
- **Migrations must be backward compatible.** For about a minute the old and
  new versions run against one database, and the new container migrates while
  the old one is still serving. Adding a column is safe. Dropping or renaming
  one the deployed code still reads takes the site down in the one way this
  setup cannot help with — split it across two deploys.
- **Graceful shutdown.** `server.shutdown: graceful` lets in-flight requests
  finish instead of having their connections cut, and flips the readiness probe
  to `OUT_OF_SERVICE` first so Caddy stops sending new work to a container that
  is on its way out.

Caddy itself needs no restart when its config changes — reload it in place:

```bash
docker compose -f docker-compose.prod.yml exec caddy caddy reload --config /etc/caddy/Caddyfile
```

---

## 4. Maps (optional)

Maps currently use Leaflet with OpenStreetMap tiles, which is free and works.
Google Maps looks more familiar to Indian users and has better local data.

If you want it: Google Cloud Console → enable **Maps JavaScript API** →
create a key → **restrict it to your domain** (an unrestricted key on a public
page is somebody else's free quota, billed to you).

```bash
NEXTTENANT_MAPS_KEY=...
```

Leave it blank and everything still works. This is the one item on the list
you can genuinely skip.

---

## 5. Before you tell anyone about it

These are not optional, and they are the ones people skip.

### 5.1 Legal pages

Under India's **DPDP Act 2023** you collect personal data (phone numbers,
email addresses, approximate locations), so you need:

- A **privacy notice** — what you collect, why, how long you keep it, and how
  someone withdraws consent or gets their data deleted
- A named **grievance officer** with a working contact address
- **Terms of use**

You are also an intermediary under the IT Act, which carries its own
obligations around takedowns.

I am not a lawyer and this is not legal advice — but a site collecting phone
numbers from strangers with no privacy policy is a real exposure, not a
formality. Get a template reviewed by someone qualified; it is a few thousand
rupees.

### 5.2 Delete the seed data

`DevDataSeeder` is `@Profile("dev")` so it will not run under `prod`. But if
you ever boot the production database with the dev profile by accident, 315
fake listings land in it. Check before launch:

```sql
SELECT count(*) FROM listings;             -- expect 0
SELECT count(*) FROM users;                -- expect 0
SELECT email FROM user_private LIMIT 5;    -- expect nothing @nexttenant.invalid
```

### 5.3 Make yourself an admin

There is no UI to promote a user — deliberately. Sign in once through the real
site, then:

```sql
UPDATE users SET is_admin = true WHERE id = '<your user id>';
```

Find your id via `user_private` by your email. Do this before you need the
moderation queue, not during an incident.

### 5.4 Check the area data

Locality centroids in V10 were **hand-placed and are accurate to a few hundred
metres**. Sublocality centroids are `NULL` and inherit their parent's, so
"near Diamond Garden" currently means "near Chembur".

Nothing breaks, but radius searches are approximate. Geocoding these properly
from an authoritative source is a pure data task and worth doing before you
market the map as precise.

### 5.5 Verify the numbers on the landing page

`liveCount` and `areaCount` are computed. So, now, is **brokerage saved** — it
used to be a hardcoded ₹48 lakh, which was a fabricated statistic presented to
visitors as fact. It now sums one month's rent across flats that actually
produced a connection.

**It will read ₹0 on launch day.** That is correct. A small true number beats
a large invented one, and it grows on its own.

### 5.6 Prove the backups work

Restore one, into a scratch database, before launch. An untested backup is a
belief, not a backup.

---

## 6. The first ten minutes after deploying

In order. Each one catches a different class of mistake:

1. `curl https://yourdomain.in/actuator/health` → `{"status":"UP"}`
2. Load the home page over HTTPS. Certificate valid, no mixed-content warnings.
3. **Sign in with a real email address of yours.** This is the single most
   important check — it proves SMTP, DNS records, the OTP secret and the
   database all work at once. Check it did not land in spam.
4. Publish a listing with a photo. Reload the page — the photo should still
   render, which proves it came back out of the bucket.
5. Redeploy the container, then reload that listing. **The photo must still be
   there.** If it vanished, storage is still on local disk.
6. From a second account, send a request on that listing. The first account
   should receive an email.
7. Accept it. The second account should receive an email, and the badge on the
   header should change.
8. Check response headers include `Strict-Transport-Security` and
   `Content-Security-Policy`.
9. Trigger a 500 deliberately and confirm the page shows nothing internal.

---

## 7. Keep it running

- **Uptime monitoring** on `/actuator/health` — UptimeRobot is free.
- **Error tracking.** Sentry's free tier is plenty. Without it you find out
  about failures when a user complains, which is usually never — they just
  leave.
- **Log retention.** `docker logs` is not a log system. `--log-opt max-size`
  at minimum, so logs cannot fill the disk and take the site down.

---

## 8. Complete environment variable list

```bash
# Profile
SPRING_PROFILES_ACTIVE=prod

# Database
DB_URL=jdbc:postgresql://host:25060/nexttenant?sslmode=require
DB_USERNAME=nexttenant
DB_PASSWORD=...

# Secrets
NEXTTENANT_OTP_SECRET=...          # openssl rand -base64 48

# Site
NEXTTENANT_BASE_URL=https://yourdomain.in

# Email
NEXTTENANT_MAIL_PROVIDER=smtp
NEXTTENANT_MAIL_FROM="NextTenant <noreply@yourdomain.in>"
NEXTTENANT_MAIL_REPLY_TO=hello@yourdomain.in
SMTP_HOST=...
SMTP_PORT=587
SMTP_USERNAME=...
SMTP_PASSWORD=...

# Photos
NEXTTENANT_PHOTO_STORAGE=s3
NEXTTENANT_S3_BUCKET=...
NEXTTENANT_S3_ENDPOINT=...         # blank for Amazon S3
NEXTTENANT_S3_REGION=auto
NEXTTENANT_S3_ACCESS_KEY=...
NEXTTENANT_S3_SECRET_KEY=...

# Optional
NEXTTENANT_MAPS_KEY=...

# Search Console verification: the token from its "HTML tag" method, without
# the surrounding markup. Blank means the tag is not rendered at all, which is
# an unattempted verification rather than a failed one.
NEXTTENANT_GSC_TOKEN=

# Privacy-respecting analytics — Plausible or Umami, self-hosted or theirs.
# No cookies, no cross-site identifier, no personal data leaving the country,
# so no consent banner is needed under the DPDP Act.
#
# BOTH must be set or analytics stays off: a script URL with no domain loads a
# tracker that reports nothing, which is a third-party request on every page
# view bought for no data.
#
# Deliberately NOT Google Analytics. GA sets identifiers and transfers data
# abroad, so it would need opt-in consent before the script loaded, and the
# banner costs more conversions than the numbers are worth at this size.
NEXTTENANT_ANALYTICS_SCRIPT=
NEXTTENANT_ANALYTICS_DOMAIN=
```

**`NEXTTENANT_BASE_URL` matters more than it used to.** Canonical tags, Open
Graph URLs and the sitemap are all built from it. A staging copy left pointing
at the production host publishes canonicals claiming to *be* production; one
with its own host and no `noindex` competes with it. Set it per environment.

---

## 9. Later: SMS and phone verification

Phone numbers are collected at signup but **not verified** —
`users.phone_verified_at` is null for everyone, honestly.

Verifying them needs SMS, and SMS in India needs **DLT registration** under
TRAI's TCCCPR rules:

1. Register your entity on an operator DLT portal (Jio, Airtel or VI — one
   registration propagates). Needs business documents: incorporation or
   proprietorship proof, PAN, usually GST. **This is why you need a company.**
2. Register a **header** (the 6-character sender ID).
3. Register each **template**, with variable placeholders, and wait for
   approval.
4. Then sign up with an aggregator — MSG91 or Gupshup.

Budget **two to four weeks**, most of it waiting. One-time registration fees
are a few thousand rupees; per-SMS is roughly ₹0.15–0.25.

When it clears: implement `SmsSender`, set the provider property, and switch
`OtpPurpose.LOGIN` back on. The code path already exists and is tested — it
was kept working precisely so this is a configuration change.

Two templates to register: the login code, and a "somebody wants your room"
notification. SMS is far better than email for the second, and the second is
what makes the marketplace actually turn over.

---

## Known gaps

Things that work but are not finished, in rough priority order:

- **Area centroids** are hand-placed approximations; sublocalities have none
  and inherit their parent's (§5.4).
- **Email notifications are one per request, no digest.** Fine at launch. If a
  popular listing makes it annoying, batch *after* the first — the first
  should always go immediately.
- **Plus-addressing** — `you+tag@gmail.com` and `you@gmail.com` are two
  accounts. Closing it means canonicalising for identity while keeping the
  delivery address separate.
- **No rate limit on requests sent**, only on OTPs. One account can ask about
  every listing in Mumbai.
- **No saved searches**, no drag-to-reorder photos.
