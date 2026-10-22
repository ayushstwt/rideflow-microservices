# syntax=docker/dockerfile:1
# Build from the repository root (the shared libs/common-events module is part of the build):
#   docker build -f services/ride-service/Dockerfile .

# ---- build: compile the service and its modules, then split the Spring Boot jar into layers ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY libs ./libs
COPY services ./services

# The BuildKit cache mount keeps the downloaded dependencies between builds.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl services/ride-service -am -DskipTests package

RUN java -Djarmode=layertools -jar services/ride-service/target/ride-service-*.jar extract --destination /layers

# ---- runtime: JRE only, non-root, one image layer per dependency group for good caching ----
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app

# Least likely to change first, so a code change only rebuilds the last layer.
COPY --from=build --chown=spring:spring /layers/dependencies/ ./
COPY --from=build --chown=spring:spring /layers/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /layers/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /layers/application/ ./

USER spring
EXPOSE 8083

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8083/actuator/health || exit 1

# exec: java becomes PID 1 and receives SIGTERM for a graceful shutdown.
# JarLauncher lives in org.springframework.boot.loader.launch since Spring Boot 3.2.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
