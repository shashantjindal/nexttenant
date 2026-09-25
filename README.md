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

## Running

```
./gradlew bootRun
```

Then open <http://localhost:8080>.

## Useful commands

| Command                  | Purpose                                        |
|--------------------------|------------------------------------------------|
| `./gradlew build`        | Compile, test, package                         |
| `./gradlew bootRun`      | Run the app with DevTools live reload          |
| `./gradlew test`         | Tests only                                     |
| `./gradlew --stop`       | Kill daemons (first thing to try when Gradle misbehaves) |
| `./gradlew dependencies` | Print the resolved dependency tree             |

## Project layout

Packages are organised **by feature**, not by layer — everything for one
capability lives in one folder, so a feature can be read or removed as a unit.

```
com.househunting
├── HouseHuntingApplication.java   scan root — do not move
└── home/                          landing page
```
