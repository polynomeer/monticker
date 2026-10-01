FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew settings.gradle.kts ./
COPY gradle/ gradle/
COPY build.gradle.kts ./
RUN ./gradlew dependencies --no-daemon -q || true
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test -q

FROM alpine:3.19 AS pinpoint-agent
# 버전은 여기 한 곳에서만 정한다. 런타임 ENTRYPOINT는 tarball이 함께 넣어주는
# **버전 없는** pinpoint-bootstrap.jar 을 쓴다(버전 붙은 jar 과 같은 파일이다 — tar 목록으로 확인).
# 예전에는 거기에 `pinpoint-bootstrap-3.1.0.jar`가 리터럴로 박혀 있어서, 이 ARG만 올리면
# 에이전트 jar 을 못 찾아 조용히(또는 javaagent 오류로) 깨졌다.
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
    AGENT_JAR=$([ -f /pinpoint-agent/pinpoint-bootstrap.jar ] \
      && echo /pinpoint-agent/pinpoint-bootstrap.jar \
      || ls /pinpoint-agent/pinpoint-bootstrap-*.jar 2>/dev/null | head -1); \
    if [ -z \"$AGENT_JAR\" ]; then echo \"[pinpoint] agent jar not found — 에이전트 없이 기동\" >&2; fi; \
    AGENT_OPTS=\"${AGENT_JAR:+-javaagent:$AGENT_JAR} \
      -Dpinpoint.agentId=${PINPOINT_AGENT_ID:-monticker-worker-1} \
      -Dpinpoint.applicationName=${PINPOINT_APP_NAME:-monticker-worker} \
      -Dpinpoint.config=/pinpoint-agent/pinpoint-root.config\"; \
  fi; \
  exec java ${AGENT_OPTS} ${JAVA_OPTS} -jar app.jar \
"]
