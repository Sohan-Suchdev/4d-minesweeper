# ---------- Stage 1: compile the bootable jar ----------
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Wrapper + build scripts first — this layer caches the dependency download
# until build.gradle.kts or settings.gradle.kts actually change.
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies

# Source last — a code-only change invalidates only the layers below this line.
COPY src src
RUN ./gradlew --no-daemon bootJar

# ---------- Stage 2: minimal JRE runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
