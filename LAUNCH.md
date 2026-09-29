# Putting NextTenant on the internet

**A complete walkthrough, assuming you have never used AWS.**

Every step says what to click, what to type, and what you should see if it
worked. Do them in order — each one depends on the ones before it.

There is a glossary at the bottom. If a word is unfamiliar, it is probably
there.

---

## Before you start

**You need:**

- A credit or debit card (AWS charges ₹2 to verify it, then refunds it)
- A phone that can receive a call or SMS
- About **₹900** for the domain
- About **3 hours of work**, spread over **2 days** — some steps involve
  waiting for other people

**Three waits you cannot rush**, which is why they come first:

| Wait | How long |
|---|---|
| Verifying your domain email (§1.2b) | minutes to a couple of hours |
| Amazon approving your email sending (§4.4) | a few hours to a day |
| Nameservers and DNS spreading across the internet | 15 minutes to a few hours |

The first one blocks the other two, so start it the moment you have bought the
domain — before you so much as open AWS.

Start both early and do everything else while they happen.

**Budget:** roughly **$31/month**, which your $200 of AWS credits covers for
about six months. If you signed up from India you will be billed in rupees by
AWS India with **18% GST added**, so think of it as about **₹3,000/month** once
the credits are gone.

---

# Part 1 — Buy a domain name

*About 15 minutes.*

A domain is the address people type. You rent it yearly.

### 1.1 Choose a name

`.in` costs around ₹800/year and says India. `.com` is around ₹1,200 and is
more familiar. Either is fine.

Pick something short and typeable over a phone call.

### 1.2 Buy it

Any registrar works — GoDaddy, Namecheap, Cloudflare. This guide uses
**GoDaddy**, and notes where another registrar differs.

1. Search your name and add it to the cart.
2. At checkout, **turn OFF every extra** — hosting, email, SSL certificates,
   "premium DNS", SEO tools, "domain protection". You need none of them.

   > The SSL one is worth refusing deliberately: it is usually the most
   > expensive upsell on the page, and Part 8 gets you a certificate free and
   > automatically. "Domain protection" is mostly repackaging two-factor
   > authentication and a transfer lock, both of which are free in your account
   > settings.

   **Keep "Domain Privacy"** if it is free — it hides your home address and
   phone number from public WHOIS lookups, which matters when the product puts
   you in contact with strangers.
3. Pay.
4. Turn on **auto-renew**, and **two-factor authentication** on the registrar
   account.

   > Domains are lost to expiry far more often than to anything dramatic. A
   > card expires, the reminder goes to spam, and somebody who watches expiry
   > lists takes the name.

> **Why not buy the domain from Amazon too?** You can, but Route 53 charges
> $0.50/month for the hosted zone where others include DNS. One less thing to
> configure and one less line on the bill.

**You should now have:** a domain, visible under *My Products* (GoDaddy) or
*Domain List* (Namecheap).

---

### 1.2b Verify your email — do this before anything else

**Check your inbox now, including spam.** Your registrar has sent a message
with a subject like *"Verify your email address"* or *"Immediate action
required"*. **Click the link in it.**

This is not an optional welcome email. ICANN's Registrar Accreditation
Agreement requires registrars to confirm the registrant's email address within
15 days of registration, and to **suspend the domain until they do**. A new
domain is therefore often already suspended — the registrar calls it
**Registrar Hold**, or *"pending RAA verification"*.

While it is on hold:

- the domain resolves to nothing
- **you cannot change nameservers**, so §1.3 below simply refuses
- after about 15 days it is suspended properly

One click fixes it, and the hold usually lifts within a couple of hours.

**Nothing needs to be uploaded.** RAA verification is an email confirmation, not
an identity check — no PAN, no Aadhaar, no documents. (`.in` domains can
separately ask for registrant details under NIXI rules, but that is a different
notice and says so.)

**If you cannot find the email**, note that it goes to the **registrant address
on the domain**, which is not always your account login. Check under **Contact
Information** on the domain, then either:

1. use **Resend verification email** on that same screen, or
2. edit the registrant email and save — re-entering the same address sends a
   fresh one, and changing it to one you can actually read fixes the case where
   the address on file is wrong, or
3. ask support: *"Please resend the RAA verification email for nexttenant.in."*

**You should now have:** a domain with no warning banner on it, and a
nameservers screen that opens rather than refusing.

---

### 1.3 Move DNS to Cloudflare

*Optional, free, and worth doing — but only if you want an email address at
your domain. Skip to Part 2 if you do not.*

**DNS** is the phone book turning `nexttenant.in` into a server address. Your
registrar provides it, but Cloudflare's is free, faster, and — the actual
reason — includes **free email forwarding**, so `hello@nexttenant.in` lands in
your normal inbox.

GoDaddy removed free forwarding from most new domains and sells a Microsoft 365
plan instead. Check **My Products → your domain → Email** first; if forwarding
is free on your account, take it and skip this section.

**Do this BEFORE Part 4.** You are about to add five DNS records for email. Move
nameservers first and you add them once. Move afterwards and you add all five
again.

1. Sign up at **cloudflare.com** (free plan) → **Add a site** → `nexttenant.in`
   → **Free**.
2. It scans your existing records, then shows **two nameservers** like
   `xxx.ns.cloudflare.com`. Copy both.
3. **GoDaddy → My Products → your domain → Nameservers → Change → "I'll use my
   own nameservers"** → paste both → Save. GoDaddy will try to talk you out of
   it; continue.
4. Wait. Usually 15 minutes, occasionally a few hours. Cloudflare emails you
   when it takes effect.

**Then set up the mailbox:**

5. Cloudflare → **Email → Email Routing → Get started**.
6. **Add a destination address** first — your normal Gmail — and **click the
   verification link Cloudflare emails to it**.

   > Do this before creating any rule. An unverified destination can still be
   > picked when you write a rule, and mail to it simply never arrives.

7. **Routing rules → Create** two custom addresses, both forwarding to that
   destination:
   - `hello@nexttenant.in` — where replies to your notification emails land,
     and what `NEXTTENANT_MAIL_REPLY_TO` points at
   - `privacy@nexttenant.in` — Part 11.3 needs a monitored address for the
     privacy policy, and it is another thirty seconds now

8. **Set the catch-all rule to Drop.**

   > Forwarding everything means every address anybody invents at your domain
   > reaches your inbox — and once the domain is public, spam finds it and
   > there is nothing to unsubscribe from. Drop, and add real addresses as you
   > need them.

`noreply@` needs no rule and no inbox: SES only ever sends from it, and replies
go to `hello@` because the app sets a reply-to.

**Routing status "Syncing"** is Cloudflare adding the MX records; it clears by
itself. **DNS records "Locked"** is normal — it means Cloudflare owns those MX
entries so they cannot be broken by accident.

> **Remember this for §4.3.** Email Routing has just added its own SPF record.
> Do not add a second one for Amazon — a domain with two SPF records is treated
> as having **none**, and your sign-in codes go to spam. Merge them into a
> single TXT record on `@`:
>
> ```
> v=spf1 include:_spf.mx.cloudflare.net include:amazonses.com ~all
> ```

**To reply *as* your domain** rather than from your personal address, after
Part 4.5: Gmail → **Settings → Accounts → Send mail as** → add
`hello@nexttenant.in` and point it at the SES SMTP credentials you create
there. Gmail emails a code to that address, which forwards to you.

**From here on, "your DNS provider" means Cloudflare** if you did this, and
your registrar if you did not. The records are identical either way.

---

# Part 2 — Create an AWS account

*About 20 minutes.*

1. Go to **aws.amazon.com** → **Create an AWS Account**.
2. Email, password, account name — use your own name or the site's.
3. Choose **Personal** account type.
4. Enter your address and phone number. Use real ones; AWS verifies them.
5. Enter your card. AWS charges ₹2 and refunds it.
6. Verify your phone — AWS calls or texts a code.
7. Choose the **Basic support plan (free)**.

Signing up from India usually creates an **AWS India** account: billed in
rupees, with GST, and an invoice you can claim against a business later.

### 2.1 Find your credits

**Top right → your account name → Billing and Cost Management → Credits.**

You should see your starting credits and their expiry date. Note that date.

> If you see nothing, the credits may need claiming through a promotional link
> or may be applied on first usage. Check the Free Tier page in the same menu
> to see exactly what your account was given — AWS changed these terms in 2025
> and older guides describe the previous scheme.

### 2.2 Set your region — and check it every time

**Top right, next to your account name**, there is a region dropdown. Choose
**Asia Pacific (Mumbai) ap-south-1**.

This is the single most common beginner mistake on AWS. Everything you create
lives in one region and is **invisible from the others**. Create a database in
Mumbai, switch to Ohio, and the page will tell you that you have no databases —
while still billing you for the one in Mumbai.

**Check that dropdown says Mumbai before every step below.**

---

# Part 3 — Protect yourself from surprise bills

*About 10 minutes. Do not skip this.*

AWS does not stop you spending money. A mistake here can cost thousands of
rupees before you notice.

### 3.1 A budget that emails you

1. Search **Billing** in the top search bar → **Budgets** → **Create budget**.
2. Choose **Use a template (simplified)** → **Monthly cost budget**.
3. Amount: **$40**.
4. Email: yours.
5. Create.

You will now be emailed when you pass 85% and 100% of $40. The architecture
below costs about $31, so any alert means something is wrong.

### 3.2 Turn on the free-tier alerts

**Billing → Billing preferences → Alert preferences** → tick **Receive AWS Free
Tier alerts** and **Receive CloudWatch billing alerts** → Save.

### 3.3 Stop using the root account

The account you just made is the **root** account. It can close the account and
change your card. You should use it as little as possible.

1. Search **IAM** → **Users** → **Create user**.
2. Name: `admin`. Tick **Provide user access to the console**.
3. Choose **I want to create an IAM user**, set a password.
4. **Attach policies directly** → tick **AdministratorAccess**.
5. Create. **Copy the sign-in URL it shows you** — it looks like
   `https://123456789012.signin.aws.amazon.com/console`. Save it somewhere.
6. Sign out. Sign back in with that URL as `admin`.

> **Missed the URL?** It is not lost — it is built from your account number.
> Go to **IAM → Dashboard** and look at the **AWS Account** box on the right:
> "Sign-in URL for IAM users in this account", with a copy button.
>
> While you are there, click **Account Alias → Create** and set it to
> `nexttenant`. Your sign-in URL becomes
> `https://nexttenant.signin.aws.amazon.com/console`, which you can type from
> memory instead of remembering twelve digits. Both forms keep working.
>
> Failing all that: the normal AWS sign-in page has an **IAM user** option
> where you type the 12-digit account ID by hand. Same result.

Then add MFA (a phone app that generates codes) to **both** accounts:
**IAM → Users → click the user → Security credentials → Assign MFA device.**

**From here on, use the `admin` sign-in.**

---

# Part 4 — Email

*About 20 minutes of work, then a wait of up to a day.*

**Do this now**, even though you will not use it until Part 10 — this is the
step with the longest wait.

Your site emails people six-digit sign-in codes. Without this, nobody can log
in at all.

### 4.1 Tell Amazon you own the domain

1. Search **SES** in the top bar. **Check the region says Mumbai.**
2. **Identities** → **Create identity**.
3. Choose **Domain**. Type your domain, without `www` and without `https://`.
4. Leave **Easy DKIM** on, **RSA_2048_BIT**, and leave **Publish DNS records to
   Route 53** unticked — your DNS is not at Amazon.
5. Create.

You now see **three CNAME records**. Keep this page open.

### 4.2 Add those records at your DNS provider

Where to find the records screen:

| | |
|---|---|
| **Cloudflare** | Your site → **DNS → Records → Add record** |
| **GoDaddy** | **My Products → your domain → DNS → Add New Record** |
| **Namecheap** | **Domain List → Manage → Advanced DNS → Add New Record** |

For each of the three: type **CNAME**.

- **Name** (GoDaddy and Namecheap call it *Host*): the AWS "Name" value **with
  `.nexttenant.in` removed from the end**.

  > **This is the one that catches nearly everyone.** All three providers append
  > your domain to whatever you type. AWS shows
  > `abc123._domainkey.nexttenant.in`; you enter **`abc123._domainkey`**. Paste
  > it whole and you create `abc123._domainkey.nexttenant.in.nexttenant.in`,
  > which never verifies and never tells you why.

- **Value** (Cloudflare: *Target*): the AWS "Value", exactly.
- **TTL**: Auto.
- **Cloudflare only:** set **Proxy status to DNS only** (grey cloud, not
  orange). Proxying a verification record hides it from Amazon and verification
  never completes.

Save all three.

### 4.3 Add two more records so your mail is not treated as spam

Same screen, two **TXT** records:

| Name / Host | Value |
|---|---|
| `@` | `v=spf1 include:amazonses.com ~all` |
| `_dmarc` | `v=DMARC1; p=none; rua=mailto:hello@nexttenant.in` |

> If Cloudflare Email Routing already added an SPF record, **edit** it rather
> than adding a second — a domain with two SPF records is treated as having
> none. Merge them into one, e.g.
> `v=spf1 include:_spf.mx.cloudflare.net include:amazonses.com ~all`.

These three things together — DKIM, SPF and DMARC — prove your emails are
really from you. **Without them Gmail rejects your sign-in codes outright.**
Not "might send to spam" — refuses.

### 4.4 Ask Amazon to let you email real people

New SES accounts are in a **sandbox**: you can only email addresses you have
personally verified, 200 a day. That is useless for a real site.

1. **SES → Account dashboard** → **Request production access**.
2. Mail type: **Transactional**.
3. Website URL: `https://nexttenant.in`.
4. Use case — describe it honestly, something like:

   > A flat-sharing marketplace for Mumbai. We send six-digit sign-in codes and
   > notifications when a user receives or accepts a request. Recipients are
   > registered users who entered their own address. No marketing email is sent.

5. Submit.

Usually approved within a day. **Continue with the rest while you wait.**

### 4.5 Create the email username and password

**SES → SMTP settings → Create SMTP credentials** → accept the defaults →
Create.

**Download the CSV.** The password is shown once and never again.

These are **not** your AWS password and not the IAM keys — SES issues a
separate pair used only for sending mail.

**Check back in a few hours:** the Identities page should say **Verified**, and
the Account dashboard should no longer say "sandbox".

---

# Part 5 — The database

*About 15 minutes of clicking, then 10 minutes of waiting.*

This stores every listing, account and message. Amazon runs and backs it up.

### 5.1 Create a security group for it

A **security group** is a firewall: a list of who may connect.

1. Search **VPC** → **Security groups** (left menu) → **Create security group**.
2. Name: `nt-db`. Description: `Database`.
3. VPC: leave the default.
4. **Leave inbound rules empty for now.** We add one in Part 7, once the server
   exists.
5. Create.

### 5.2 Create the database

1. Search **RDS** → **Create database**.
2. **Standard create**.
3. Engine: **PostgreSQL**, version **16.x**.
4. Templates: **Dev/Test**.

   > Not "Production". That template turns on Multi-AZ, which runs a second
   > standby copy and **doubles the bill** for failover you do not need before
   > you have users.

5. **Settings**
   - DB instance identifier: `nexttenant-db`
   - Master username: `postgres`
   - Master password: generate a long one and **save it now** in a password
     manager
6. **Instance configuration**: Burstable classes → **db.t4g.micro**
7. **Storage**
   - 20 GiB, gp3
   - **Untick "Enable storage autoscaling"** — it only ever grows, silently,
     and it grows your bill with it
8. **Connectivity**
   - Public access: **No**

     > The database must never be reachable from the internet. Your server
     > talks to it over Amazon's private network.
   - Existing VPC security groups: **remove `default`**, choose **`nt-db`**
9. **Additional configuration**
   - Initial database name: `nexttenant`

     > Easy to miss and worth getting right — it saves creating it by hand
     > later.
   - Backup retention: **7 days**
   - Deletion protection: **tick it**
10. Create database.

Takes about 10 minutes. Status goes `Creating` → `Available`.

### 5.3 Write down the address

Click `nexttenant-db` → **Connectivity & security** → copy the **Endpoint**.
It looks like:

```
nexttenant-db.abc123xyz.ap-south-1.rds.amazonaws.com
```

You need this in Part 8.

---

# Part 6 — Photo storage

*About 10 minutes.*

Listing photos cannot live on the server — they would be deleted every time
you update the site. They go in **S3**, Amazon's file storage.

### 6.1 Create the bucket

1. Search **S3** → **Create bucket**.
2. Region: **Mumbai**.
3. Name: must be globally unique across all of AWS. Use something like
   `nexttenant-photos-4821` with random digits.
4. **Block all public access: leave TICKED.**

   > The bucket stays private deliberately. Photos are served *through* the
   > app, which is what makes stripping location data from images, moderating
   > them, and taking them down actually mean something. A public bucket URL is
   > a permanent link to an image that nobody can ever retract.

5. Create. **Write the bucket name down.**

### 6.2 Give the server permission to use it

Rather than creating a password for the bucket, you give the server itself
permission. A **role** hands the server temporary credentials that rotate
automatically and cannot be copied off the machine.

1. Search **IAM** → **Roles** → **Create role**.
2. Trusted entity: **AWS service**. Use case: **EC2**. Next.
3. Skip the permissions list for now. Next.
4. Name: `nt-app-role`. Create.
5. Click into `nt-app-role` → **Add permissions** → **Create inline policy**.
6. Click the **JSON** tab and replace everything with this, putting **your**
   bucket name in:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
    "Resource": "arn:aws:s3:::nexttenant-photos-4821/*"
  }]
}
```

7. Name it `nt-s3-access`. Create.

Three actions, one bucket. If the server is ever broken into, that is the
entire extent of what the attacker gets.

---

# Part 7 — The server

*About 20 minutes.*

### 7.1 Its firewall

1. **VPC → Security groups → Create security group**.
2. Name: `nt-app`.
3. **Inbound rules** — add three:

| Type | Source | Meaning |
|---|---|---|
| SSH | **My IP** | You, to administer it |
| HTTP | Anywhere-IPv4 | Visitors, and certificate renewal |
| HTTPS | Anywhere-IPv4 | Visitors |

> SSH is **My IP**, not Anywhere. A server with SSH open to the world is being
> guessed at within minutes of booting.

4. Create.

### 7.2 Let the database accept connections from the server

1. **Security groups** → select **`nt-db`** → **Inbound rules** → **Edit**.
2. **Add rule**: Type **PostgreSQL**, Source → start typing `nt-app` and pick
   that **security group** from the list.
3. Save.

> The source is a *group*, not an address. The database now accepts connections
> from your server and nothing else — and it keeps working if you ever replace
> the server, because the new one joins the same group.

### 7.3 Launch it

1. Search **EC2** → **Launch instance**.
2. Name: `nexttenant`.
3. Image: **Amazon Linux 2023**.
4. Instance type: **t3.small**.

   > `t2.micro` is tempting because it is often free, but 1GB of memory is not
   > enough. The app resizes uploaded photos in memory and will be killed by
   > the system on the first real upload.

5. Key pair → **Create new key pair** → name `nt-key`, type **RSA**, format
   **.pem** → Create. **The file downloads once.** Lose it and you lose access
   to the server.
6. **Network settings → Edit** → Select existing security group → **`nt-app`**.
7. **Configure storage**: 20 GiB gp3.
8. **Advanced details** → scroll to **IAM instance profile** → choose
   **`nt-app-role`**.

   > Easy to miss, and photo uploads fail without it.

9. Launch instance.

### 7.4 Give it a permanent address

By default the server's IP changes every time it restarts, which would break
your domain.

1. **EC2 → Elastic IPs** (left menu) → **Allocate Elastic IP address** →
   Allocate.
2. Select it → **Actions → Associate Elastic IP address** → choose your
   `nexttenant` instance → Associate.

**Write this IP down.** It is your server's address.

> An Elastic IP is free while attached to a running server and charged when it
> is not. If you ever delete the server, release the IP too.

---

# Part 8 — Put the app on the server

*About 30 minutes, mostly waiting for things to download.*

### 8.1 Connect to it

Open **PowerShell** on your PC and go to wherever `nt-key.pem` downloaded:

```powershell
cd ~\Downloads
```

Windows will refuse to use the key file until its permissions are locked down:

```powershell
icacls nt-key.pem /inheritance:r /grant:r "$env:USERNAME:R"
```

Then connect, using your Elastic IP:

```powershell
ssh -i nt-key.pem ec2-user@13.234.56.78
```

Type `yes` when asked about authenticity.

**You should see** a prompt like `[ec2-user@ip-172-31-x-x ~]$`. You are now
typing commands *on the server*.

### 8.2 Install the tools

```bash
sudo dnf update -y
sudo dnf install -y docker git postgresql15
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user
```

Install Docker Compose:

```bash
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
```

Now **log out and back in** so the Docker permission takes effect:

```bash
exit
```
```powershell
ssh -i nt-key.pem ec2-user@13.234.56.78
```

Check it worked:

```bash
docker --version && docker compose version
```

### 8.3 Add swap memory

2GB is tight for this app. Swap is emergency memory on disk — without it, Linux
kills the app outright with no explanation when memory runs short.

```bash
sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

### 8.4 Prepare the database

Using the endpoint from Part 5.3:

```bash
psql -h nexttenant-db.abc123xyz.ap-south-1.rds.amazonaws.com -U postgres -d nexttenant
```

Enter your master password. At the `nexttenant=>` prompt:

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
\q
```

> **PostGIS is what makes "flats within 3km" work.** The app refuses to start
> without it, so this must happen before the first launch.

**You should see** `CREATE EXTENSION` twice. If instead you see a connection
timeout, the security group rule in Part 7.2 is wrong.

### 8.5 Get the code

```bash
git clone https://github.com/<you>/nexttenant.git
cd nexttenant
```

> Use whatever your repository is actually called — if it is still named
> `nexttenant`, clone that and `cd` into it. The folder name does not matter
> to anything except the two commands below, **with one exception**: Docker
> Compose derives its project name from the directory, and the project name is
> what the certificate volume is attached to. So do not rename the folder after
> you have deployed, or Caddy loses its certificates and asks Let's Encrypt for
> new ones.

If the repository is private, GitHub will ask for a username and a **personal
access token** — your GitHub password will not work. Create one at
**github.com → Settings → Developer settings → Personal access tokens**.

### 8.6 Write the settings file

```bash
nano nt.env
```

Paste this, **replacing every `<...>`**:

```bash
SPRING_PROFILES_ACTIVE=prod

DB_URL=jdbc:postgresql://<your-rds-endpoint>:5432/nexttenant?sslmode=require
DB_USERNAME=postgres
DB_PASSWORD=<your master password>

NEXTTENANT_OTP_SECRET=<paste the random string from below>

NEXTTENANT_BASE_URL=https://nexttenant.in

NEXTTENANT_MAIL_PROVIDER=smtp
NEXTTENANT_MAIL_FROM=NextTenant <noreply@nexttenant.in>
NEXTTENANT_MAIL_REPLY_TO=hello@nexttenant.in
SMTP_HOST=email-smtp.ap-south-1.amazonaws.com
SMTP_PORT=587
SMTP_USERNAME=<SES SMTP username from the CSV>
SMTP_PASSWORD=<SES SMTP password from the CSV>

NEXTTENANT_PHOTO_STORAGE=s3
NEXTTENANT_S3_BUCKET=<your bucket name>
NEXTTENANT_S3_REGION=ap-south-1
NEXTTENANT_S3_ENDPOINT=
NEXTTENANT_S3_ACCESS_KEY=
NEXTTENANT_S3_SECRET_KEY=
```

Save with **Ctrl+O, Enter, Ctrl+X**.

Three things that are deliberately as they look:

- **`NEXTTENANT_S3_ENDPOINT` is blank.** That means "real Amazon S3".
- **The S3 key and secret are blank.** The role from Part 6.2 supplies them.
  This is the secure way round.
- **`sslmode=require`** encrypts the database connection.

Generate the secret:

```bash
openssl rand -base64 48
```

Copy the output into `NEXTTENANT_OTP_SECRET`. **The app refuses to start if
you leave the example value in** — every sign-in code would be forgeable by
anyone who has read the source code.

Lock the file down:

```bash
chmod 600 nt.env
```

### 8.7 Set up the web address config

```bash
cp Caddyfile.example Caddyfile
nano Caddyfile
```

Replace the example domain with `nexttenant.in`. Save and exit.

### 8.8 Start it

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

The first build takes **5–10 minutes** — it is downloading Java and compiling.

Watch it come up:

```bash
docker compose -f docker-compose.prod.yml logs -f app
```

**You should see** `Started NextTenantApplication`. Press **Ctrl+C** to stop
watching (this does not stop the app).

Check it is alive:

```bash
curl http://localhost:8080/actuator/health
```

**You should see** `{"status":"UP"}`. `UP` means the database answered too, not
just that the app is running.

> If you see `DOWN` or nothing, jump to **Troubleshooting** at the bottom.

---

# Part 9 — Point your domain at it

*5 minutes of work, then up to a few hours of waiting.*

Open the same records screen you used in Part 4.2.

1. **Delete the registrar's parking records first.** A new domain arrives
   pointing at the registrar's own advert — usually an **A record on `@`** and
   a **CNAME on `www`**. Adding your records without removing those leaves two
   answers for one name, and visitors get whichever the internet feels like.

   **Leave your SES and email records alone** — only the `@` and `www` entries
   go.

2. Add two records:

| Type | Name / Host | Value | TTL |
|---|---|---|---|
| A | `@` | your Elastic IP | Auto |
| A | `www` | your Elastic IP | Auto |

3. **Cloudflare only:** set both to **DNS only** (grey cloud), not Proxied
   (orange).

   > Proxying routes visitors through Cloudflare, which sounds good and breaks
   > the next step: Caddy proves it controls the domain by answering a request
   > on port 80, and behind the proxy that request never reaches your server.
   > You can turn proxying on later, once the certificate exists.

4. Save.

Check it from PowerShell on your PC:

```powershell
nslookup nexttenant.in
```

**You should see** your Elastic IP. If not, wait 15 minutes and try again.

Once it resolves, Caddy gets the HTTPS certificate automatically — within about
a minute. On the server:

```bash
docker compose -f docker-compose.prod.yml logs caddy
```

**You should see** a line saying a certificate was obtained.

**Now open `https://nexttenant.in` in your browser.** You should see the site,
with a padlock.

---

# Part 10 — Check everything actually works

Do all nine. Each one catches a different failure, and some only show up once.

**1. Health**
```powershell
curl https://nexttenant.in/actuator/health
```
Expect `{"status":"UP"}`.

**2. The padlock.** Load the site. Click the padlock — the certificate should
be valid and issued to your domain.

**3. Sign in with your own real email address.**

This is the most valuable single check on the list: it proves email, DNS
records, the secret and the database all at once.

**Check your spam folder.** If the code landed there, something in Part 4.3 is
wrong.

**4. Publish a listing with photos.** Go through the whole wizard.

**5. Reload the listing.** The photos must appear — proving they came back out
of S3.

**6. Restart the app and reload again:**
```bash
docker compose -f docker-compose.prod.yml restart app
```
**The photos must still be there.** If they vanished, storage is still writing
to the server's disk and you will lose every photo on your next update.

**7. Request the listing from a second account** (a different email). The first
account should get an email.

**8. Accept it.** The second account gets an email, and the number on the
Requests button changes.

**9. Confirm your settings are not public:**
```powershell
curl -s -o /dev/null -w "%{http_code}" https://nexttenant.in/actuator/env
```
**Expect `302`.** If it says `200`, your database password is readable by
anyone and you must stop and fix it.

---

# Part 11 — Before you tell anyone about it

### 11.1 Make yourself an admin

There is no button for this, deliberately. Sign in through the real site first,
then on the server:

```bash
psql -h <your-rds-endpoint> -U postgres -d nexttenant
```
```sql
UPDATE users SET is_admin = true
 WHERE id = (SELECT user_id FROM user_private WHERE email = 'you@nexttenant.in');
```

Do it now, not during an incident.

### 11.2 Check no test data got in

```sql
SELECT count(*) FROM listings;
SELECT count(*) FROM users;
```

Expect only what you created in Part 10. If you see ~315 listings, the app
started with the wrong profile — check `SPRING_PROFILES_ACTIVE=prod` in
`nt.env`.

### 11.3 Legal pages

You collect phone numbers, email addresses and approximate locations from
strangers. Under India's **DPDP Act 2023** you need:

- A **privacy policy** — what you collect, why, and how someone deletes it
- A named **grievance officer** with a real contact address
- **Terms of use**

I am not a lawyer and this is not legal advice. But a site collecting phone
numbers with no privacy policy is genuine exposure, not a box-tick. A reviewed
template costs a few thousand rupees and is worth it before you advertise
anywhere.

### 11.4 Two numbers to be aware of

The **"brokerage saved"** figure on the home page will read **₹0**. That is
correct — it counts real connections and grows on its own. It used to be a
hardcoded ₹48 lakh, which was a made-up statistic being shown to visitors as
fact.

**Area locations are approximate** — accurate to a few hundred metres, and
sublocalities use their parent locality's centre. Distance search works, but do
not advertise the map as precise.

---

# Keeping it running

### Updating the site

```bash
cd ~/nexttenant
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

About 20 seconds of downtime. Database migrations run automatically.

### Useful commands

| | |
|---|---|
| See what is running | `docker compose -f docker-compose.prod.yml ps` |
| Watch the logs | `docker compose -f docker-compose.prod.yml logs -f app` |
| Restart | `docker compose -f docker-compose.prod.yml restart app` |
| Free disk space | `docker system prune -a` |

### Watch your spending weekly

**Billing → Cost Explorer**, grouped by service. Four things quietly eat a
budget:

- **RDS storage autoscaling** — should be off (Part 5.2)
- **Old snapshots** — kept and billed forever; delete them
- **Unattached Elastic IPs** — free attached, charged when not
- **CloudWatch log groups** — default to never expiring; set 7 days

### Free monitoring worth adding

- **uptimerobot.com** — free, emails you if the site goes down. Point it at
  `https://nexttenant.in/actuator/health`.
- **sentry.io** — free tier, tells you when users hit errors. Without it you
  find out when someone complains, which is usually never; they just leave.

---

# Troubleshooting

**"This domain cannot have its nameservers updated" / "Registrar Hold" /
"pending RAA Verification"**
Your registrant email is unverified, so the registrar has suspended the domain.
Click the link in the verification email they sent — see §1.2b. Nothing needs
uploading; it is an email confirmation, not an identity check.

**`ssh` says "Permission denied (publickey)"**
The username is `ec2-user`, not `root` or your name. On Windows, run the
`icacls` command from Part 8.1.

**`psql` just hangs, then times out**
The `nt-db` security group is missing the rule from Part 7.2, or its source is
an IP instead of the `nt-app` group.

**App logs say "Refusing to start: the OTP secret is still the development
default"**
Working as intended. Generate a real one with `openssl rand -base64 48` and put
it in `nt.env` (Part 8.6).

**App logs mention `postgis` or `geography`**
Part 8.4 was skipped or run against the wrong database.

**Health says `DOWN`**
Usually the database. Check `DB_URL` in `nt.env` — the endpoint, `:5432`, and
`/nexttenant` at the end.

**Site loads on `http://` but not `https://`**
Caddy could not get a certificate. Check `nslookup nexttenant.in` returns your
IP, that ports 80 and 443 are open in `nt-app`, then
`docker compose -f docker-compose.prod.yml restart caddy`.

**Sign-in emails never arrive**
Check spam first. Then: is SES still in sandbox (Part 4.4)? Does the Identities
page say **Verified**? Does `NEXTTENANT_MAIL_FROM` use the domain you
verified? Then `docker compose -f docker-compose.prod.yml logs app | grep -i mail`.

**Photos upload but do not appear**
The IAM instance profile from Part 7.3 step 8 was not attached, or the bucket
name in the policy does not match. Attaching it needs a reboot of the instance.

**Build runs out of memory**
Swap was not set up — Part 8.3.

---

# Glossary

| Term | What it means here |
|---|---|
| **Domain** | The address people type. Rented yearly. |
| **DNS** | The phone book turning your domain into a server address. |
| **A record** | A DNS entry pointing a domain at an IP address. |
| **CNAME** | A DNS entry pointing one name at another. Used to prove you own a domain. |
| **IP address** | A server's numeric address, like `13.234.56.78`. |
| **Elastic IP** | An AWS address that does not change when the server restarts. |
| **EC2** | A rented computer in Amazon's data centre. |
| **Instance** | One of those computers. |
| **RDS** | A database Amazon runs, updates and backs up for you. |
| **PostgreSQL** | The database software. |
| **PostGIS** | The add-on that makes "within 3km" possible. |
| **S3** | Amazon's file storage. Where photos live. |
| **Bucket** | One folder in S3. |
| **SES** | Amazon's email sending service. |
| **SMTP** | The standard protocol for sending email. |
| **IAM** | Who is allowed to do what in your AWS account. |
| **Role** | Permissions given to a *machine* instead of a person. |
| **Security group** | A firewall — a list of who may connect, and on which port. |
| **VPC** | Your own private network inside AWS. |
| **SSH** | A secure remote terminal onto the server. |
| **Docker** | Packages the app with everything it needs so it runs the same anywhere. |
| **Container** | One running copy of that package. |
| **Caddy** | The web server handling HTTPS. |
| **Certificate** | The file that makes the padlock appear. |
| **Region** | Which of Amazon's data centres. Yours is Mumbai. |
| **Sandbox** | SES's restricted starting mode — only verified addresses. |
