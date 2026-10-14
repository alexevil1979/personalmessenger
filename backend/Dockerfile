FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/build/libs/app.jar /app/app.jar
RUN useradd --system --uid 10001 --home /app messenger \
    && chown -R messenger:messenger /app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=12 CMD curl -fsS http://127.0.0.1:8080/api/v1/health || exit 1
USER messenger
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
