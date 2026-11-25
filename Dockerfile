# BugTrack container image. Build: docker build -t bugtrack .
# Runs with the "prod" settings (see src/main/resources/application-prod.properties); configure it with
# environment variables (DB_URL, DB_USERNAME, DB_PASSWORD, BUGTRACK_ADMIN_EMAIL, ...; see README "Deploying").

# ---- Build stage: compile and package the app ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
# Download dependencies first so they are cached between builds when only the code changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests && cp target/bug-tracking-system-*.jar /app.jar

# ---- Run stage: only the Java runtime and the app ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app bugtrack \
    && mkdir -p /app /data/attachments \
    && chown -R bugtrack /app /data
WORKDIR /app
COPY --from=build --chown=bugtrack /app.jar app.jar
USER bugtrack
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
