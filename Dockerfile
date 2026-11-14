FROM eclipse-temurin:21-jdk AS build

WORKDIR /src
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts .editorconfig ./
# Resolved before the sources are copied, so a source-only change reuses the cache.
RUN ./gradlew --no-daemon --console=plain dependencies --configuration runtimeClasspath

COPY config ./config
COPY src ./src
# `build` runs ktlint, detekt and every test (the mail server and the database of the tests run inside
# the test process), so a broken image never starts.
RUN ./gradlew --no-daemon --console=plain build installDist

FROM eclipse-temurin:21-jre

# The scanner needs no privileges of its own. /app/data is created owned by mailscan: a new empty
# Docker volume mounted there takes over that ownership.
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin mailscan \
 && mkdir -p /app/data \
 && chown mailscan:mailscan /app/data
COPY --from=build /src/build/install/mailscan /opt/mailscan
WORKDIR /app
USER mailscan

# Memory, for the 256 MB limit of the container:
#   UseSerialGC           one collector thread and none of G1's bookkeeping; a heap this small is collected in milliseconds
#   Xmx128m               a fixed ceiling, so the heap does not grow towards the limit before it is collected
#   TieredStopAtLevel=1   the quick C1 compiler only: less memory for compiled code and for the compiler itself;
#                         top speed does not matter next to the half a minute the model takes per email
#   -UsePerfData          no statistics file in /tmp, which the read-only root file system could not take
#   MALLOC_ARENA_MAX=2    glibc otherwise gives every thread a malloc arena of its own
# The root file system is read-only, so the SQLite driver unpacks its native library into the data volume.
ENV JAVA_OPTS="-XX:+UseSerialGC -Xmx128m -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Dorg.sqlite.tmpdir=/app/data" \
    MALLOC_ARENA_MAX=2

ENTRYPOINT ["/opt/mailscan/bin/mailscan"]
