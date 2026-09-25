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

    // ---- Testing ------------------------------------------------------------
    // Bundles JUnit 5, AssertJ, Mockito and Spring's test context framework.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    // Gradle defaults to the JUnit 4 runner for historical reasons.
    // Spring Boot 3+ ships JUnit 5, so we must opt in explicitly or no test runs.
    useJUnitPlatform()
}
