# Multi-stage build for the Java/Spring Boot backend (admin module, migration step 1).
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
# Warm the dependency cache before copying sources for faster incremental builds.
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
# curl is used by the docker-compose healthcheck (GET /readyz).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/savefood-backend-*.jar app.jar
# Reached only through nginx on the internal docker network (same as Python backend).
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "app.jar"]
