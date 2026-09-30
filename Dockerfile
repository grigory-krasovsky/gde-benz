# syntax=docker/dockerfile:1

# ---- Build stage: compile and package the Spring Boot fat jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Warm the dependency cache first (only re-runs when pom.xml changes).
# Tolerate partial resolution — the package step below fetches anything missing.
COPY pom.xml .
RUN mvn -B dependency:go-offline || true

# Then build the application.
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Runtime stage: slim JRE image with just the jar ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as a non-root user.
RUN useradd -r -u 1001 appuser
USER appuser

COPY --from=build /build/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
