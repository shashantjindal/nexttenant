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

## ⚠️ JAVA_HOME must point at JDK 17 or newer

A Gradle build uses **two** JVMs, and they are chosen differently:

| JVM               | Chosen by                  | Job                                |
|-------------------|----------------------------|------------------------------------|
| Gradle daemon     | `JAVA_HOME`                | Runs Gradle, loads plugins         |
| Compile toolchain | `java { toolchain { … } }` | Compiles this project's source     |

The `toolchain` block in `build.gradle.kts` only governs the second. The Spring
Boot Gradle plugin must be loaded by the **daemon**, and requires Java 17+.

If `JAVA_HOME` points at an old JDK you will see:

```
> Dependency requires at least JVM runtime version 17. This build uses a Java 8 JVM.
```

Verify with:

```
echo $JAVA_HOME          # bash
$env:JAVA_HOME           # PowerShell
```

Fix permanently (PowerShell, then reopen the terminal):

```
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Java\jdk-21.0.10", "User")
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
