FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew settings.gradle.kts ./
COPY gradle/ gradle/
COPY build.gradle.kts ./
RUN ./gradlew dependencies --no-daemon -q || true
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test -q

FROM alpine:3.19 AS pinpoint-agent
ARG PINPOINT_VERSION=3.1.0
RUN apk add --no-cache curl tar && \
    curl -fSL \
      "https://github.com/pinpoint-apm/pinpoint/releases/download/v${PINPOINT_VERSION}/pinpoint-agent-${PINPOINT_VERSION}.tar.gz" \
      -o /tmp/pp.tar.gz && \
    mkdir -p /pinpoint-agent && \
    tar -xzf /tmp/pp.tar.gz -C /pinpoint-agent --strip-components=1 && \
    rm /tmp/pp.tar.gz

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S monticker && adduser -S monticker -G monticker
COPY --from=build          /app/build/libs/*.jar  app.jar
COPY --from=pinpoint-agent /pinpoint-agent        /pinpoint-agent
USER monticker

ENTRYPOINT ["sh", "-c", "\
  if [ \"${PINPOINT_ENABLE:-false}\" = \"true\" ]; then \
    AGENT_OPTS=\"-javaagent:/pinpoint-agent/pinpoint-bootstrap-3.1.0.jar \
      -Dpinpoint.agentId=${PINPOINT_AGENT_ID:-monticker-worker-1} \
      -Dpinpoint.applicationName=${PINPOINT_APP_NAME:-monticker-worker} \
      -Dpinpoint.config=/pinpoint-agent/pinpoint-root.config\"; \
  fi; \
  exec java ${AGENT_OPTS} ${JAVA_OPTS} -jar app.jar \
"]
