# HouseHunting

A Mumbai flat-takeover marketplace: people leaving a shared flat list the spot
they are vacating, and people looking for a room take it over directly. No
brokers, no owners, no PGs.

## Stack

| Layer     | Choice                                   |
|-----------|------------------------------------------|
| Language  | Java 21 (LTS)                            |
| Framework | Spring Boot 4.1.1                        |
| Views     | Thymeleaf (server-rendered HTML)         |
| Build     | Gradle 9.8.0 (via wrapper)               |
| Database  | PostgreSQL 16 + PostGIS *(phase 2)*      |

Server-rendered rather than a JSON API + SPA: there is exactly one client
(the browser), and organic search is the primary acquisition channel.

## Prerequisites

- **JDK 21** — optional; Gradle downloads it if missing (see below)
- **Docker Desktop** — for PostgreSQL/PostGIS *(phase 2)*

## Java version handling (no JAVA_HOME setup required)

A Gradle build uses **two** JVMs, chosen by different mechanisms:

| JVM               | Chosen by                             | Job                            |
|-------------------|---------------------------------------|--------------------------------|
| Gradle daemon     | `gradle/gradle-daemon-jvm.properties` | Runs Gradle, loads plugins     |
| Compile toolchain | `java { toolchain { … } }`            | Compiles this project's source |

Both are declared **in this repository**, so the build does not depend on the
machine's `JAVA_HOME`. If JDK 21 is not installed, Gradle downloads it.

This matters when you work on several projects targeting different Java
versions: a project's *compile* JDK is independent of `JAVA_HOME`, so a single
machine-wide `JAVA_HOME` serves every project whatever Java version it targets.
`JAVA_HOME` only has to satisfy the Gradle version itself.

Regenerate the daemon criteria with:

```
./gradlew updateDaemonJvm --jvm-version=21
```

## Database

PostgreSQL 16 + PostGIS 3.4, run from `docker-compose.yml`:

```
docker compose up -d        start
docker compose ps           status + health
docker compose down         stop, KEEP data
docker compose down -v      stop, DESTROY data
```

Data lives in the named volume `db-data`, not in the container — deleting the
container loses nothing, `down -v` loses everything.

Connect with psql:

```
docker exec -it househunting-db psql -U househunting -d househunting
```

### Schema migrations

Flyway owns the schema; Hibernate only validates against it
(`ddl-auto: validate`). Migrations live in
`src/main/resources/db/migration` and run automatically at startup.

**Never edit a migration that has already run.** Flyway stores a checksum of
each file, and changing an applied one fails startup everywhere it already ran.
Correct a mistake by adding the next version, not by editing history.

Naming is `V<n>__<description>.sql` — note the **two** underscores; with one,
Flyway silently ignores the file.

### Spring Boot 4 and auto-configuration modules

Spring Boot 4 split auto-configuration out of a single `spring-boot-autoconfigure`
jar into per-technology modules. Putting a library on the classpath is no longer
enough to activate its Spring integration:

| Want            | Need                                                    |
|-----------------|---------------------------------------------------------|
| Flyway to run   | `flyway-core` + `flyway-database-postgresql` + `spring-boot-flyway` |

Omitting the `spring-boot-*` module fails **silently** — the app starts and the
feature simply does not exist. To diagnose, run with `--debug` and search the
condition evaluation report: absent from all three sections (positive, negative,
unconditional) means the auto-configuration was never even a candidate.

## Running it locally

All commands are PowerShell, run from the project root. Note the `.\` before
`gradlew` — PowerShell will not run an executable from the current directory
without it. Windows PowerShell 5.1 also does not support `&&`, so use separate
lines or `;`.

### First time

```
cd C:\dev\househunting
docker compose up -d
.\gradlew bootRun
```

The first start applies all migrations and seeds about 390 listings across 98
Mumbai localities, each with three or four photographs. That takes **a minute or
two** — the photos are drawn and then pushed through the real upload pipeline,
EXIF stripping and all, so a bug in it shows up in dev rather than waiting for a
real upload. It logs its progress. Later starts skip the seed entirely.

Then open <http://localhost:8080>.

### Every time after that

```
cd C:\dev\househunting
docker compose up -d      # skip if Docker Desktop already has it running
.\gradlew bootRun
```

Stop the app with `Ctrl+C`. Leave Postgres running, or stop it with
`docker compose stop`.

### Signing in

There is no password. Enter any Indian mobile number and the six-digit code is
printed to the terminal running the app:

```
==================== SMS (not actually sent) ====================
  to:   +919820044556
  CODE: 261688
  text: 261688 is your HouseHunting code. It expires in 10 minutes.
=================================================================
```

The code is **not** in the database: `otp_challenges` stores only an HMAC of it.
That is deliberate, and it means the terminal is the only place to look.

To see only the codes:

```
.\gradlew bootRun | Select-String "CODE:"
```

### Limits you will hit while testing

These are real production limits, not debug settings, so they apply locally too:

| Limit                  | Value                    |
|------------------------|--------------------------|
| Resend cooldown        | 30 seconds per number    |
| Codes per number       | 5 per hour               |
| Codes per IP           | 20 per hour              |
| Wrong guesses          | 5, then the code is dead |

If a code stops arriving, it is almost always the 30-second cooldown. Use a
different number, or wait. They can be loosened for local work in
`application.yml` under `househunting.otp.*` — leave the production values alone.

### Becoming a moderator

`/admin/reports` needs ROLE_ADMIN, which is granted only by direct database
access — there is deliberately no UI to promote a user.

```
docker exec -it househunting-db psql -U househunting -d househunting
```

```sql
-- find your account by the number you signed in with
SELECT u.id, u.first_name, p.phone_e164
FROM users u JOIN user_private p ON p.user_id = u.id
WHERE p.phone_e164 = '+919820044556';

UPDATE users SET is_admin = TRUE WHERE id = '<that id>';
```

Log out and back in — the role is read into the session at sign-in.

### Starting over with clean data

```
docker compose down -v
docker compose up -d
.\gradlew bootRun
```

`down -v` destroys the volume and therefore all data. The next start re-runs
every migration and re-seeds. The seed uses a fixed random seed, so you get the
same 308 listings every time.

To clear uploaded photos as well:

```
Remove-Item -Recurse -Force .\data\photos
```

### When something looks wrong

| Symptom | Cause |
|---------|-------|
| `Port 8080 was already in use` | An earlier run is still going. `Get-NetTCPConnection -LocalPort 8080 -State Listen \| ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }` |
| `Connection refused` on startup | Postgres is not up. `docker compose up -d` |
| Gradle behaving strangely | `.\gradlew --stop`, then retry |
| Template change not showing | Templates are served from `build\resources`. Stop and restart |
| No OTP in the terminal | The 30-second resend cooldown. Try a different number |
| No verification email | Same idea — it prints to the terminal under `EMAIL (not actually sent)` |
| Maps look like OpenStreetMap | No Google key is set. See below |

## Google Maps

Maps fall back to Leaflet and OpenStreetMap when no key is configured, so a
fresh clone runs with working maps and nothing to sign up for. To use Google:

1. In Google Cloud Console, enable **Maps JavaScript API** on a project with
   billing attached. There is a monthly free allowance; past it, map loads are
   charged.
2. Create an API key.
3. **Restrict it.** Application restrictions → HTTP referrers, listing
   `http://localhost:8080/*` and your real domain. API restrictions → Maps
   JavaScript API only. The key is visible in the page source, because the API
   runs in the browser — the referrer restriction, not secrecy, is what stops a
   copied key working anywhere else.
4. Set it in the environment rather than in `application.yml`, which is
   committed:

```
$env:GOOGLE_MAPS_API_KEY = "your-key-here"
.\gradlew bootRun
```

Two maps switch over: the approximate-location circle on a listing page, and
the draggable pin in wizard step 1. The pin is the one that benefits most —
somebody is looking for their own building, and Mumbai society names and
building footprints are far better covered in Google's data.

## Useful commands

PowerShell, from the project root.

| Command                     | Purpose                                              |
|-----------------------------|------------------------------------------------------|
| `.\gradlew bootRun`         | Run the app, with DevTools reloading on code changes |
| `.\gradlew test`            | Run all tests (needs Postgres up)                    |
| `.\gradlew build`           | Compile, test and package                            |
| `.\gradlew --stop`          | Kill Gradle daemons — first thing to try when Gradle misbehaves |
| `.\gradlew dependencies`    | Print the resolved dependency tree                   |
| `docker compose up -d`      | Start Postgres                                       |
| `docker compose stop`       | Stop Postgres, keep the data                         |
| `docker compose down -v`    | Stop Postgres and **destroy** the data               |
| `docker compose logs -f db` | Follow database logs                                 |

Connect to the database directly:

```
docker exec -it househunting-db psql -U househunting -d househunting
```

## Data model and the privacy boundary

The central constraint: **a phone number or exact address must never reach a
public page.** That is enforced structurally, not by convention.

Public and private data live in **separate tables**. Loading a listing to
render it cannot put the exact coordinates in memory, because they are not in
that table. Reaching them requires an explicit join written by someone who
meant to write it.

| Public            | Private             | Released when                  |
|-------------------|---------------------|--------------------------------|
| `users`           | `user_private`      | request reaches `CONNECTED`    |
| `listings`        | `listing_private`   | request reaches `CONNECTED`    |

Two details that are easy to get wrong:

- `users.work_email_verified_at` is **public** (it is the trust badge shown to
  strangers); `user_private.work_email` is **private**. Badge and address are
  different facts with different audiences.
- `listing_private.society_name` is private. A society name *is* an address:
  publishing it would make the approximate circle decorative.

### Approximate locations

`listings.approx_location` is a point offset 120-300m from the truth,
maintained by a trigger on `listing_private` so application code can neither
forget it nor set it to the real coordinates.

The offset is **deterministic** — derived from `md5(listing_id)`, so it is the
same on every request. This is the security property: a per-request random
offset would be defeated by loading the page repeatedly and averaging, because
random noise cancels and the true point falls out. With one fixed offset there
is only ever one sample.

Search runs against `approx_location`, so the browse path never reads an exact
location at all.

### Contact release

`interest_requests` is the **sole authority** for whether two users may read
each other's phone number, and `status = 'CONNECTED'` is the only condition
that permits it. There is deliberately no separate grants table: the release is
symmetric, so a second table would be derived state that could disagree.

### Conventions

- Money is `INTEGER` whole rupees. Never floating point.
- Timestamps are `TIMESTAMPTZ`, stored UTC. `TIMESTAMP` means different
  instants to different readers.
- Enums are `VARCHAR` + `CHECK`, not native Postgres `ENUM`: adding a value is
  an ordinary migration, and it maps onto JPA `@Enumerated(EnumType.STRING)`.
- Primary keys are UUID. Sequential ids leak row counts and let anyone walk
  `1..N` to scrape the whole inventory.
- Invariants belong in the database. A `CHECK` holds against a bug, a script,
  or someone in `psql` at midnight; a comment holds against nothing.

## Project layout

Packages are organised **by feature**, not by layer — everything for one
capability lives in one folder, so a feature can be read or removed as a unit.

```
com.househunting
├── HouseHuntingApplication.java   scan root — do not move
├── area/          the 98 Mumbai localities, and fuzzy autocomplete
├── auth/          phone OTP, sessions, security config
├── common/        Rupees, Hashing, RelativeTime
├── dashboard/     the signed-in dashboard
├── dev/           DevDataSeeder — @Profile("dev") only
├── geo/           GeoPoints: the only sanctioned way to build a coordinate
├── home/          landing page
├── listing/       listings, detail page, lifecycle
│   ├── dto/       ListingCardDto, ListingDetailDto — no private fields
│   └── wizard/    the six-step listing wizard
├── moderation/    reports, the two-strikes rule, admin queue
├── photo/         upload, EXIF stripping, storage
├── request/       interest requests and ContactRelease
├── mail/          EmailSender, and the console one used in dev
├── search/        filters, keyset paging, radius widening
├── shortlist/     the heart on a card
├── user/          User and UserPrivate
└── workemail/     work email verification, and the badge behind it
```

Templates mirror the same shape under `src/main/resources/templates`.

## Where the important decisions live

If you read four files to understand how this application protects people,
read these:

| File | Why |
|------|-----|
| `request/ContactRelease.java` | The only place one person's phone number is released to another |
| `photo/ImageProcessor.java` | Strips the GPS coordinates a phone embeds in every photo |
| `db/migration/V3__listings.sql` | The public/private split and the deterministic location jitter |
| `auth/SecurityConfig.java` | What is public, what is not, and why CSRF and session fixation matter |
