FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew settings.gradle.kts ./
COPY gradle/ gradle/
COPY build.gradle.kts ./
RUN ./gradlew dependencies --no-daemon -q || true
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S monticker && adduser -S monticker -G monticker
COPY --from=build /app/build/libs/*.jar app.jar
USER monticker
EXPOSE 8083
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]
