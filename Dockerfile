# Kotlin/Native cannot compile linuxX64 binaries on an ARM Linux host, so always build on amd64.
# Jammy is used because GNU tar in newer Ubuntu images fails under Docker Desktop's x86 emulation on Apple Silicon.
FROM --platform=linux/amd64 eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon --version
COPY src ./src
RUN ./gradlew --no-daemon linkReleaseExecutableLinuxX64

FROM --platform=linux/amd64 debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 couchbridge
COPY --from=build /src/build/bin/linuxX64/releaseExecutable/couchbridge.kexe /usr/local/bin/couchbridge
USER couchbridge
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/couchbridge"]
