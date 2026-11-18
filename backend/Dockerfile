# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
# Maven Central occasionally answers "not found" for an artifact that exists (seen twice on CI): keep downloads in a
# BuildKit cache and try up to three times before failing the build.
RUN --mount=type=cache,target=/root/.m2 \
    for attempt in 1 2 3; do mvn -B -ntp -DskipTests package && exit 0; echo "Maven attempt $attempt failed; retrying"; sleep 15; done; exit 1
FROM eclipse-temurin:21-jre
RUN groupadd --system luma && useradd --system --gid luma --home-dir /app luma
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
USER luma
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
