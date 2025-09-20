# syntax=docker/dockerfile:1

# Build stage: compile the Spring Boot sample-app and produce an executable JAR
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# Install required tools for Gradle wrapper
RUN apt-get update && apt-get install -y unzip && rm -rf /var/lib/apt/lists/*

# Copy Gradle wrapper and settings
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./

# Copy sources
COPY src ./src
COPY sample-app ./sample-app

RUN chmod +x gradlew

# Build only the sample-app module and skip tests for faster build
RUN ./gradlew :sample-app:bootJar -x test --no-daemon

# Normalize jar name for the next stage
RUN JAR_FILE=$(ls sample-app/build/libs/*.jar | head -n 1) && cp "$JAR_FILE" /app.jar

# Runtime stage: run the application using a lightweight JRE image
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app.jar /app/app.jar

# Default Spring Boot port
EXPOSE 8080

# Allow passing extra JVM options via JAVA_OPTS
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
