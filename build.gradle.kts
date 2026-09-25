plugins {
    // Core Gradle plugin: compile Java, run tests, build a jar.
    java

    // Spring Boot's plugin. Its main jobs:
    //   - repackages our jar into an executable "fat jar" (app + all dependencies)
    //   - adds the `bootRun` task so we can run the app from Gradle
    // Requires Gradle 8.14+ or 9.x, and a Gradle daemon on Java 17+.
    id("org.springframework.boot") version "4.1.1"
}

group = "com.househunting"
version = "0.0.1-SNAPSHOT"
description = "HouseHunting — Mumbai flat-takeover marketplace"

java {
    toolchain {
        // Controls which JDK COMPILES our code. Note this does NOT control which
        // JVM Gradle itself runs on — that comes from JAVA_HOME. See README.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // ---- Version management -------------------------------------------------
    // The Bill of Materials: one curated set of mutually-tested versions.
    // Declared once, then referenced by every configuration that needs it, so
    // Spring dependencies below can omit versions entirely.
    val springBootBom = platform("org.springframework.boot:spring-boot-dependencies:4.1.1")

    implementation(springBootBom)

    // `developmentOnly` is a standalone configuration created by the Spring Boot
    // plugin. Unlike `testImplementation`, it does NOT extend `implementation`,
    // so it inherits no version constraints — the BOM must be applied to it too.
    developmentOnly(springBootBom)

    // ---- Web layer ----------------------------------------------------------
    // Spring MVC + embedded Tomcat. This is what makes the app an HTTP server.
    implementation("org.springframework.boot:spring-boot-starter-web")

    // ---- View layer ---------------------------------------------------------
    // Thymeleaf renders HTML on the server. Chosen over a JSON API + React
    // because we have exactly one client (the browser) and SEO is critical.
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // ---- Developer experience ----------------------------------------------
    // Restarts the app when compiled classes change, and disables template
    // caching so edited HTML shows on refresh. `developmentOnly` guarantees
    // this never reaches the production jar.
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // ---- Persistence --------------------------------------------------------
    // JPA (the spec) + Hibernate (the implementation) + HikariCP (connection
    // pooling). Used for entity CRUD; the PostGIS search queries are written by
    // hand in SQL, where an ORM would only get in the way.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // `runtimeOnly` is load-bearing, not a micro-optimisation: our code compiles
    // against java.sql and JPA interfaces only. Keeping the driver off the
    // compile classpath means an accidental `import org.postgresql.*` fails to
    // compile, so database portability is enforced by the build rather than by
    // code review.
    runtimeOnly("org.postgresql:postgresql")

    // ---- Security and sessions ----------------------------------------------
    // Authentication, CSRF protection and the security filter chain. The login
    // form is switched OFF in SecurityConfig: we authenticate by OTP.
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Sessions in PostgreSQL rather than in Tomcat memory, so a session
    // survives a restart and is visible to every instance. That is what lets a
    // removed account lose access immediately.
    implementation("org.springframework.session:spring-session-jdbc")
    implementation("org.springframework.boot:spring-boot-session")

    // JdbcSessionAutoConfiguration lives in its OWN module. Without it the app
    // silently falls back to Tomcat in-memory sessions: nothing fails, the
    // cookie is JSESSIONID instead of SESSION, and the discovery happens in
    // production the first time a deploy logs everybody out.
    implementation("org.springframework.boot:spring-boot-session-jdbc")

    // ---- Spatial types ------------------------------------------------------
    // PostGIS geography columns have no Java equivalent. hibernate-spatial
    // teaches Hibernate to read and write JTS geometry types (Point, Polygon)
    // against them; JTS itself arrives transitively.
    implementation("org.hibernate.orm:hibernate-spatial")

    // ---- Schema migrations --------------------------------------------------
    // Since Flyway 10 the database-specific support is a separate artifact.
    // Without flyway-database-postgresql, startup fails with
    // "Unsupported Database: PostgreSQL 16" — which looks like a version
    // mismatch but is actually a missing module.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Spring Boot 4 split auto-configuration out of one monolithic jar into
    // per-technology modules. flyway-core alone puts Flyway on the classpath but
    // NOT its Spring integration, so migrations never run — silently, with no
    // error. This module is what registers FlywayAutoConfiguration.
    implementation("org.springframework.boot:spring-boot-flyway")

    // ---- Testing ------------------------------------------------------------
    // Bundles JUnit 5, AssertJ, Mockito and Spring's test context framework.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 also split the TEST slices into per-technology modules, and
    // renamed their packages. spring-boot-starter-test alone no longer provides
    // @DataJpaTest, @AutoConfigureTestDatabase or TestEntityManager - each now
    // lives in the module for the technology it tests.
    testImplementation("org.springframework.boot:spring-boot-data-jpa-test")  // @DataJpaTest
    testImplementation("org.springframework.boot:spring-boot-jpa-test")       // TestEntityManager
    testImplementation("org.springframework.boot:spring-boot-jdbc-test")      // @AutoConfigureTestDatabase
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")    // @AutoConfigureMockMvc

    // MockMvc request post-processors such as csrf() and user(). Not part of
    // spring-boot-starter-test: it belongs to Spring Security, not Boot.
    testImplementation("org.springframework.security:spring-security-test")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    // Gradle defaults to the JUnit 4 runner for historical reasons.
    // Spring Boot 3+ ships JUnit 5, so we must opt in explicitly or no test runs.
    useJUnitPlatform()
}
