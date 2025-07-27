FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

COPY gradlew .
COPY gradle ./gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY shared ./shared
COPY bot ./bot
COPY gradle/libs.versions.toml ./gradle/libs.versions.toml
COPY .env /app/.env

RUN chmod +x gradlew && ./gradlew build --no-daemon

CMD ["java", "-jar", "build/libs/bot.jar"]