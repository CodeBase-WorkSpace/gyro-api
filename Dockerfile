FROM eclipse-temurin:25-jdk-alpine AS build

WORKDIR /workspace

# Keep dependency resolution in a reusable layer; source changes do not invalidate it.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre-alpine AS runtime

WORKDIR /app
RUN apk add --no-cache wget \
    && addgroup -S gyro \
    && adduser -S -G gyro -h /app gyro

COPY --from=build --chown=gyro:gyro /workspace/build/libs/*.jar /app/api.jar

USER gyro:gyro
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/api.jar"]
