# Multi-stage Dockerfile for the Long Exposure parser/worker.
#
# Stage 1: Gradle build using a JDK image (no host JDK required)
# Stage 2: Slim JRE runtime carrying just the built jar(s)

FROM gradle:8.10-jdk21-alpine AS builder
WORKDIR /build
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle --no-daemon installDist

FROM eclipse-temurin:21-jre-alpine AS runtime
# No libpcap — pcap parsing is implemented in-tree, no native deps required.
WORKDIR /app
COPY --from=builder /build/build/install/long-exposure-parser /app/
ENV PATH="/app/bin:${PATH}"
ENTRYPOINT ["/app/bin/long-exposure-parser"]
