# Deploying NextTenant on AWS

A standalone runbook. You do not need `DEPLOY.md` — everything is here.

Written around the **$200 of starting credits**, so cost is called out at every
step and the expensive services are deliberately avoided.

Region is **`ap-south-1` (Mumbai)** throughout. Set it once in the console's
top-right corner and check it before creating anything; resources made in the
wrong region are invisible from the right one and quietly bill you.

---

## 0. What the $200 actually buys

The architecture below costs about **$30/month**, so the credits cover roughly
**six months** of running this site.

| | ap-south-1, per month |
|---|---|
| EC2 `t3.small` (2GB) — the app | ~$15 |
| RDS `db.t4g.micro` + 20GB — the database | ~$15 |
| S3 — photos | <$1 |
| SES — email | <$1 |
| Route 53 — DNS | $0.50 |
| **Total** | **~$31** |

**A cheaper variant exists.** Run PostgreSQL on the same EC2 box instead of
RDS and it drops to about **$16/month — roughly twelve months of credits.**
What you give up is automated backups, point-in-time recovery and one-click
version upgrades, and you take on the job of proving your own `pg_dump`
restores. §3.3 covers both; pick deliberately.

> **Check what your account actually gets.** AWS reorganised its free tier in
> 2025 — credits, the length of the window, and which services have an
> always-free allowance all changed, and older guides describe the previous
> scheme. Read your account's Free Tier page before budgeting around any of
> this.

### What would burn the credits in weeks

These are the four things that turn a $30 month into a $130 month. None of
them is used below, and that is the whole point of this architecture:

| Service | Cost | Why it is skipped |
|---|---|---|
| **NAT Gateway** | **~$40/mo** + per-GB | Only needed for private subnets. The single most common surprise bill on AWS. |
| **Application Load Balancer** | ~$17/mo | Caddy on the instance does TLS for free. |
| **Multi-AZ RDS** | 2× the database | Failover you do not need before you have users. |
| **ECS Fargate** | more than EC2 here | Orchestration for one container. |

---

## 1. Before you create a single resource

### 1.1 Set a billing alarm

Do this first. On AWS, unlike a droplet, nothing stops you spending money.

**Billing and Cost Management → Budgets → Create budget → Zero spend budget**,
then a second **Cost budget** at $20/month with alerts at 50%, 80% and 100%.
Send them to an address you actually read.

### 1.2 Stop using your root account

Root can close the account and change billing. Create an IAM user with
`AdministratorAccess`, turn on MFA for both, and sign in as the IAM user from
here on.

### 1.3 Request SES production access — today

This is the only step with a waiting period, so start it before anything else.

New SES accounts are in a **sandbox**: you can only send to addresses you have
verified, capped at 200/day. Your sign-in codes will not reach real users until
this clears.

**SES → Account dashboard → Request production access.** Describe the use
honestly — transactional sign-in codes and notifications to registered users,
with no marketing mail. Usually approved within 24 hours.

---

## 2. The shape of it

```
        Route 53  →  Elastic IP
                         │
                   ┌─────┴──────────────────────────┐
                   │  EC2 t3.small (public subnet)  │
                   │    Caddy  :443 → :8080         │
                   │    Docker: nexttenant        │
                   │    IAM role → S3               │
                   └─────┬──────────────────────────┘
                         │ private VPC traffic
                   ┌─────┴──────────┐   ┌──────────┐   ┌──────────┐
                   │ RDS PostgreSQL │   │    S3    │   │   SES    │
                   │  + PostGIS     │   │  photos  │   │  email   │
                   └────────────────┘   └──────────┘   └──────────┘
```

One instance, one database, two managed services. No load balancer, no NAT, no
container orchestrator.

---

## 3. Build it

### 3.1 Security groups

**VPC → Security groups.** Create two, in this order.

**`hh-app`** — inbound:

| Type | Port | Source |
|---|---|---|
| SSH | 22 | **My IP** |
| HTTP | 80 | `0.0.0.0/0` |
| HTTPS | 443 | `0.0.0.0/0` |

Port 22 from *your IP only*. Open to the world it is scanned within minutes.
Port 80 is needed because Caddy uses it for certificate renewal.

Note that **8080 is not here.** The app binds to `127.0.0.1` and is reachable
only through Caddy — otherwise anyone can skip your TLS by hitting the port
directly.

**`hh-db`** — inbound:

| Type | Port | Source |
|---|---|---|
| PostgreSQL | 5432 | **the `hh-app` security group** |

Source is the *group*, not an IP. The database then accepts connections from
the app instance and from nothing else, and it keeps working if the instance
is replaced.

### 3.2 S3 bucket and its IAM role

**S3 → Create bucket**, region `ap-south-1`, name something unique like
`nexttenant-photos-<random>`. Leave **Block all public access ON.**

The bucket stays private on purpose. Photos are served through the app's
`/photos/**` endpoint, which is what keeps EXIF stripping, moderation and
takedowns meaningful — a public bucket URL is a permanent link that nobody can
retract.

**IAM → Roles → Create role → AWS service → EC2.** Attach an inline policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
    "Resource": "arn:aws:s3:::nexttenant-photos-<random>/*"
  }]
}
```

Call it `hh-app-role`. Three actions on one bucket — if the instance is ever
compromised, that is the whole blast radius.

**A role rather than an access key**, and this is why the app supports it: a
long-lived key lives in a file, gets copied into a backup, and is still valid a
year after whoever made it forgot. A role gives the instance short-lived
credentials that rotate themselves and cannot leave the box. Leave
`NEXTTENANT_S3_ACCESS_KEY` blank and the app picks the role up automatically.

### 3.3 The database

#### Option A — RDS (recommended)

**RDS → Create database → Standard create → PostgreSQL 16**

| Setting | Value | Why |
|---|---|---|
| Template | **Dev/Test** | Production template defaults to Multi-AZ, doubling cost |
| Instance | `db.t4g.micro` | ARM, cheapest; unrelated to the app's architecture |
| Storage | 20GB gp3 | **Turn OFF storage autoscaling** — it only grows, and silently |
| Multi-AZ | **No** | Doubles the bill for failover you do not need yet |
| Public access | **No** | Reached over the VPC; never exposed to the internet |
| Security group | `hh-db` | |
| Backup retention | 7 days | Free up to the size of the database |
| Deletion protection | **On** | |

Set a strong master password and keep it somewhere real.

#### Option B — PostgreSQL on the EC2 box

Saves ~$15/month and roughly doubles how long the credits last. Add to the
compose file in §3.6 and skip this section's RDS steps.

The honest trade: you own backups. If you take this path, set up `pg_dump` to
S3 on a cron **before your first real signup**, and restore it once into a
scratch database to prove it works. An untested backup is a belief.

### 3.4 SES

1. **SES → Identities → Create identity → Domain**, enter your domain.
2. Enable **Easy DKIM**. AWS gives you three CNAME records.
3. Add them at your DNS provider, plus an SPF `TXT` record
   (`v=spf1 include:amazonses.com ~all`) and a DMARC `TXT` on `_dmarc`
   (`v=DMARC1; p=none; rua=mailto:you@yourdomain.in`).

Skip these and Gmail rejects your sign-in codes outright. Not "might" — modern
inboxes refuse unauthenticated bulk mail.

4. **SES → SMTP settings → Create SMTP credentials.** These are *not* your IAM
   keys; SES issues a separate username and password. Save them once — the
   password is never shown again.

Endpoint: `email-smtp.ap-south-1.amazonaws.com`, port 587.

### 3.5 The instance

**EC2 → Launch instance**

| Setting | Value |
|---|---|
| AMI | Amazon Linux 2023 |
| Type | `t3.small` |
| Key pair | create one, keep the `.pem` |
| Security group | `hh-app` |
| IAM instance profile | `hh-app-role` |
| Storage | 20GB gp3 |

**Why `t3.small` (x86) and not `t4g.small` (ARM)?** Graviton is ~20% cheaper
and worth moving to later. But your laptop builds x86 images, and an x86 image
will not run on ARM — you would need `docker buildx --platform linux/arm64`.
For a first deploy, skip that class of confusion. The $3/month is not the thing
to optimise today.

2GB is the floor. The app processes 12MB image uploads in memory, and 1GB
gets you an OOM kill under the first real upload.

Then **allocate an Elastic IP and associate it** with the instance — without
one the public IP changes on every stop/start and your DNS points at nothing.
An Elastic IP is free *while attached to a running instance* and billed when
it is not, so release it if you ever tear the instance down.

### 3.6 Set the instance up

```bash
ssh -i hh.pem ec2-user@<elastic-ip>
```

```bash
sudo dnf update -y
sudo dnf install -y docker git postgresql15
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user
exit    # log back in for the group to take effect
```

**Add swap.** 2GB is tight for a JVM, and the Linux OOM killer terminates the
process with no Java stack trace — a genuinely miserable thing to debug:

```bash
sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

**Enable PostGIS**, before the app ever starts. Flyway's first migration fails
without it:

```bash
psql -h <rds-endpoint> -U postgres -d postgres
```
```sql
CREATE DATABASE nexttenant;
\c nexttenant
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
```

### 3.7 Ship the app

Building on the instance is simplest — no registry, no architecture mismatch:

```bash
git clone <your repo> nexttenant
cd nexttenant
docker build -t nexttenant:latest .
```

2GB plus swap will build it, slowly. If Gradle runs out of memory, build on
your laptop instead and push to ECR (500MB storage is within the free
allowance).

Write `/home/ec2-user/hh.env` — see §4 — then:

```bash
chmod 600 hh.env
docker run -d --name nexttenant \
  --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  --env-file /home/ec2-user/hh.env \
  --log-opt max-size=10m --log-opt max-file=3 \
  nexttenant:latest
```

`127.0.0.1` so the app is unreachable except through Caddy. The log options
matter more than they look: without them Docker's JSON logs grow until they
fill the disk and take the site down.

### 3.8 TLS

```bash
sudo dnf install -y 'dnf-command(copr)'
sudo dnf copr enable -y @caddy/caddy
sudo dnf install -y caddy
```

`/etc/caddy/Caddyfile`:

```
yourdomain.in {
    reverse_proxy 127.0.0.1:8080
}
```

```bash
sudo systemctl enable --now caddy
```

Caddy obtains and renews the certificate by itself. Point DNS at the Elastic IP
first, or the certificate request fails.

### 3.9 DNS

Route 53 costs $0.50/month per hosted zone. **Cloudflare DNS is free** and
works identically here — use it unless you want everything in one console.

Either way: an **A record** for `yourdomain.in` → your Elastic IP, and another
for `www` if you want it.

---

## 4. Environment file

```bash
SPRING_PROFILES_ACTIVE=prod

# Database (Option A: RDS)
DB_URL=jdbc:postgresql://<rds-endpoint>:5432/nexttenant?sslmode=require
DB_USERNAME=postgres
DB_PASSWORD=<master password>

# Secret. Generate with: openssl rand -base64 48
NEXTTENANT_OTP_SECRET=<48 random bytes>

# Absolute links in emails. An inbox has no base URL.
NEXTTENANT_BASE_URL=https://yourdomain.in

# Email, via SES over SMTP
NEXTTENANT_MAIL_PROVIDER=smtp
NEXTTENANT_MAIL_FROM=NextTenant <noreply@yourdomain.in>
NEXTTENANT_MAIL_REPLY_TO=hello@yourdomain.in
SMTP_HOST=email-smtp.ap-south-1.amazonaws.com
SMTP_PORT=587
SMTP_USERNAME=<SES SMTP username>
SMTP_PASSWORD=<SES SMTP password>

# Photos, via S3. No keys: the instance role supplies them.
NEXTTENANT_PHOTO_STORAGE=s3
NEXTTENANT_S3_BUCKET=nexttenant-photos-<random>
NEXTTENANT_S3_REGION=ap-south-1
NEXTTENANT_S3_ENDPOINT=
NEXTTENANT_S3_ACCESS_KEY=
NEXTTENANT_S3_SECRET_KEY=
```

`NEXTTENANT_S3_ENDPOINT` stays **blank** for real Amazon S3 — it is only set
for R2, Spaces or B2.

`NEXTTENANT_MAIL_FROM` must be on the domain you verified in SES. An
unverified sender is rejected outright.

`sslmode=require` on the database because the connection crosses a network you
do not control, even inside a VPC.

Two things are deliberately absent. **`NEXTTENANT_MAPS_KEY`** — maps fall
back to Leaflet with OpenStreetMap, which is free and works; add Google later
if you want it, and restrict the key to your domain when you do. And the S3
keys, because the instance role supplies them.

---

## 5. Before you tell anyone

### 5.1 Legal pages

Under India's **DPDP Act 2023** you collect personal data — phone numbers,
email addresses, approximate locations — so you need a privacy notice saying
what you collect and how someone withdraws consent, a named grievance officer
with a working address, and terms of use. You are also an intermediary under
the IT Act, which carries takedown obligations.

I am not a lawyer and this is not legal advice. But a site collecting phone
numbers from strangers with no privacy policy is real exposure, not a
formality. A reviewed template costs a few thousand rupees.

### 5.2 Check no seed data got in

`DevDataSeeder` is `@Profile("dev")` and will not run under `prod` — but if the
production database is ever booted with the dev profile by accident, 315 fake
listings land in it.

```sql
SELECT count(*) FROM listings;            -- expect 0
SELECT count(*) FROM users;               -- expect 0
```

### 5.3 Make yourself an admin

There is no UI to promote a user, deliberately. Sign in through the real site
first, then:

```sql
UPDATE users SET is_admin = true
 WHERE id = (SELECT user_id FROM user_private WHERE email = 'you@yourdomain.in');
```

Do this before you need the moderation queue, not during an incident.

### 5.4 Know what the data is worth

Locality centroids were hand-placed and are accurate to a few hundred metres.
Sublocality centroids are `NULL` and inherit their parent's, so "near Diamond
Garden" currently means "near Chembur". Nothing breaks; radius search is
approximate. Worth geocoding properly before marketing the map as precise.

The landing page's **brokerage saved** figure is computed from real
connections and will read **₹0** on launch day. That is correct — it used to be
a hardcoded ₹48 lakh, which was a fabricated statistic shown to visitors as
fact.

---

## 6. The first ten minutes

In order. Each catches a different class of mistake:

1. `curl https://yourdomain.in/actuator/health` → `{"status":"UP"}`
   *(`UP` means the database answered too, not just the JVM.)*
2. Home page over HTTPS, valid certificate, no mixed-content warnings.
3. **Sign in with a real email address of yours.** The single most valuable
   check — it proves SMTP, DKIM, the OTP secret and the database at once.
   Confirm it did not land in spam.
4. Publish a listing with a photo. Reload — the photo must render, proving it
   came back out of S3.
5. **Restart the container, reload that listing.** The photo must still be
   there. If it vanished, storage is still on local disk.
6. From a second account, request that listing. The first should get an email.
7. Accept it. The second should get an email and the header badge should move.
8. `curl -sI https://yourdomain.in | grep -i strict-transport` — HSTS present.
9. `curl -s -o /dev/null -w '%{http_code}' https://yourdomain.in/actuator/env`
   → **302, not 200.** A 200 means your database URL is public.

---

## 7. Keeping the credits alive

**Watch these four.** They are what quietly eats a budget:

- **RDS storage autoscaling** — off. It only ever grows.
- **Snapshots** — manual ones are kept forever and billed forever. Delete them.
- **Unattached Elastic IPs** — free while attached, billed while not.
- **CloudWatch log retention** — defaults to *never expire*. Set 7 or 14 days
  on any log group you create.

**Check weekly:** Billing → Cost Explorer, grouped by service. A line you do
not recognise is the point of the exercise.

**Free allowances worth knowing:** 100GB/month of data transfer out across the
account, and SES at roughly $0.10 per 1,000 emails — neither will be your
problem at launch.

### When the credits run out

Six months in, decide. The options, cheapest first:

1. **Move Postgres onto the EC2 box** — saves ~$15/month, costs you backups.
2. **Switch to `t4g` (Graviton)** on both — ~20% off, needs an ARM build.
3. **Buy a 1-year Savings Plan** if the site is working — ~30% off EC2.
4. **Leave.** Nothing here is AWS-specific except SES. The photo storage speaks
   the plain S3 API, so R2 or Spaces is an endpoint change, and the mailer
   speaks SMTP, so any provider is four variables.

That last point is worth keeping true. It is the reason the code has adapters
rather than SDK calls scattered through it.

---

## 8. Later: SMS and phone verification

Phone numbers are collected at signup but **not verified** —
`users.phone_verified_at` is null for everyone, honestly, because nothing has
proved those numbers.

**Do not reach for Amazon SNS.** Sending SMS to Indian numbers still requires
**DLT registration** under TRAI's TCCCPR rules regardless of provider, and SNS
gives you no advantage there over an Indian aggregator that handles the
paperwork daily.

1. Register your entity on an operator DLT portal (Jio, Airtel or VI — one
   registration propagates). Needs incorporation or proprietorship proof, PAN,
   usually GST. **This is the step that requires a company.**
2. Register a **header** — the six-character sender ID.
3. Register each **template** and wait for approval.
4. Sign up with MSG91 or Gupshup.

Budget **two to four weeks**, most of it waiting. Registration is a few
thousand rupees one-off; SMS runs roughly ₹0.15–0.25 each.

When it clears: implement `SmsSender`, set the provider property, and switch
`OtpPurpose.LOGIN` back on. That path is already written and tested — it was
kept working so this is configuration, not a project.

Register two templates: the login code, and "somebody wants your room". The
second is far better over SMS than email, and it is the one that makes the
marketplace actually turn over.

---

## Known gaps

Not blockers, in rough priority order:

- **Area centroids** are approximations; sublocalities inherit their parent's.
- **One email per request, no digest.** Fine at launch. If a popular listing
  makes it annoying, batch *after* the first — the first should always go
  immediately.
- **Plus-addressing** — `you+tag@gmail.com` and `you@gmail.com` are two
  accounts.
- **No rate limit on requests sent**, only on OTPs. One account can ask about
  every listing in Mumbai.
- **No saved searches**, no drag-to-reorder photos.
