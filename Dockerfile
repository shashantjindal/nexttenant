# =============================================================================
# HouseHunting
#
# Two stages. The first has Gradle, the JDK and the whole source tree; the
# second has a JRE and one jar. Only the second is shipped, so the image that
# runs in production does not contain a compiler, the build cache, or the
# source code — roughly 700MB against roughly 250MB, and a much smaller set of
# things that can have a vulnerability.
# =============================================================================


# ---- Stage 1: build ---------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# The wrapper and the dependency declarations are copied FIRST, on their own,
# and dependencies resolved before any source arrives. Docker caches each layer
# and invalidates everything after the first file that changed — so with this
# order, editing a Java file re-runs the compile but not the download. The other
# order re-downloads every dependency on every single build.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./

RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src ./src

# Tests are NOT run here. They need a real PostgreSQL with PostGIS, which is
# not available inside an image build — and a test suite that silently skipped
# for want of a database would be worse than one that never ran. Tests belong
# in CI, before the image is built.
RUN ./gradlew bootJar --no-daemon -x test


# ---- Stage 2: run -----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# A non-root user. If anything in the application is ever exploited, the
# process should not also own the filesystem it is running on. Cheap, and
# impossible to retrofit calmly after an incident.
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build --chown=app:app /build/build/libs/*.jar app.jar

USER app

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx. The JVM's default heap is a
# fraction of the *host* memory, which inside a container with a limit means it
# happily sizes a heap larger than the limit and gets OOM-killed by the kernel
# — a hard kill with no Java stack trace, which is a miserable thing to debug.
# 75% leaves room for metaspace, thread stacks and the image processing buffers.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Exec form, so the JVM is PID 1 and receives SIGTERM directly. Under the shell
# form the shell is PID 1, signals are not forwarded, and every deploy ends in
# a 10-second timeout and a SIGKILL — in-flight requests dropped each time.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
