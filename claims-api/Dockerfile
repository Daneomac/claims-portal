# syntax=docker/dockerfile:1

# ---- Stage 1: build the jar and split it into layers ------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Dependencies first: this layer is rebuilt only when pom.xml or the wrapper
# change, not on every source edit.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw --batch-mode dependency:go-offline

# Tests run in CI, not in the image build.
COPY src/ src/
RUN ./mvnw --batch-mode package -DskipTests

# Spring Boot's "tools" jarmode splits the fat jar into layers ordered by how
# often they change (dependencies, loader, snapshots, application code), so a
# code-only change rebuilds one small image layer.
RUN mv target/*-SNAPSHOT.jar target/app.jar && java -Djarmode=tools -jar target/app.jar extract --layers --destination /build/extracted

# ---- Stage 2: runtime (JRE only, non-root) ----------------------------------
# Alpine variant: smaller, and BusyBox wget is available for the healthcheck
# (the Ubuntu JRE image has neither curl nor wget).
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Fixed numeric IDs; no shell login, no home directory contents to speak of.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app -H -s /sbin/nologin app

COPY --from=build --chown=app:app /build/extracted/dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/application/ ./

USER 10001:10001
EXPOSE 8080

# Size the heap from the container's memory limit instead of the host's RAM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"


ENTRYPOINT ["java", "-jar", "/app/app.jar"]
