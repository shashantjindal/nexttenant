// Gradle reads this file FIRST, before build.gradle.kts, to learn what the build contains.

plugins {
    // Auto-provisions a JDK if the toolchain requested in build.gradle.kts is not
    // installed locally. This is what decouples the build from the machine's
    // JAVA_HOME — the build declares the Java version it needs, and Gradle either
    // finds it or downloads it. Without this, a wrong JAVA_HOME is a build failure.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "nexttenant"
