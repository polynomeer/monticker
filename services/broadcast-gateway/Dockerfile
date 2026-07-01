FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:17-jre
COPY --from=build /src/build/libs/broadcast-gateway-0.0.1-SNAPSHOT-all.jar /app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app.jar"]
