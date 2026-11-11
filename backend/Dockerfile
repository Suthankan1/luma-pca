FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package
FROM eclipse-temurin:21-jre
RUN groupadd --system luma && useradd --system --gid luma --home-dir /app luma
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
USER luma
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
