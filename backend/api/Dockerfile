# ── Build stage ────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew settings.gradle.kts ./
COPY gradle/ gradle/
COPY build.gradle.kts ./
RUN ./gradlew dependencies --no-daemon -q || true
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test -q

# ── Pinpoint agent stage ────────────────────────────────────────
# 별도 스테이지에서 다운로드 → 레이어 캐시로 재빌드 비용 최소화.
FROM alpine:3.19 AS pinpoint-agent
ARG PINPOINT_VERSION=3.1.0
RUN apk add --no-cache curl tar && \
    curl -fSL \
      "https://github.com/pinpoint-apm/pinpoint/releases/download/v${PINPOINT_VERSION}/pinpoint-agent-${PINPOINT_VERSION}.tar.gz" \
      -o /tmp/pp.tar.gz && \
    mkdir -p /pinpoint-agent && \
    tar -xzf /tmp/pp.tar.gz -C /pinpoint-agent --strip-components=1 && \
    rm /tmp/pp.tar.gz

# ── Runtime stage ──────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S monticker && adduser -S monticker -G monticker

COPY --from=build          /app/build/libs/*.jar  app.jar
COPY --from=pinpoint-agent /pinpoint-agent        /pinpoint-agent

USER monticker
EXPOSE 8080

# PINPOINT_ENABLE=true 일 때만 에이전트 주입 (기본값 false — 로컬/CI 기동 속도 우선).
# OTel → Jaeger 트레이싱은 -javaagent 없이도 micrometer-tracing-bridge-otel 이 담당.
# 두 트레이싱 시스템은 독립 계층(Pinpoint=APM, Jaeger=분산추적)으로 공존한다.
ENTRYPOINT ["sh", "-c", "\
  if [ \"${PINPOINT_ENABLE:-false}\" = \"true\" ]; then \
    AGENT_OPTS=\"-javaagent:/pinpoint-agent/pinpoint-bootstrap-3.1.0.jar \
      -Dpinpoint.agentId=${PINPOINT_AGENT_ID:-monticker-api-1} \
      -Dpinpoint.applicationName=${PINPOINT_APP_NAME:-monticker-api} \
      -Dpinpoint.config=/pinpoint-agent/pinpoint-root.config\"; \
  fi; \
  exec java ${AGENT_OPTS} ${JAVA_OPTS} -jar app.jar \
"]
