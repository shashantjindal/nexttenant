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

- **JDK 21** — required both to compile and to run Gradle itself
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
