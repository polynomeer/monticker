# ADR-009: K8s DNS as Service Discovery and NGINX Ingress as API Gateway

## Status
Accepted

## Context

MSA 서비스(`trading-service`, `quant-engine`)를 K8s에 배포했지만(ADR-001 후속), 두 가지 문제가 있었다:

1. **Service Discovery 미구성**: MSA 서비스 URL이 ConfigMap에 없어 API 서버가 항상 로컬 서비스를 직접 호출했다. K8s 배포 후에도 실질적으로 모놀리스 모드로 동작했다.
2. **API Gateway 미흡**: 기존 NGINX Ingress는 단순 라우팅만 수행했고, Rate Limiting·Request ID 전파·보안 헤더·WebSocket 지원이 없었다.

별도 Service Mesh(Istio)나 API Gateway 솔루션(Kong) 없이 이 두 문제를 해결할 방법이 필요하다.

## Decision

### Service Discovery — K8s DNS

별도 서비스 레지스트리(Consul, Eureka) 없이 K8s DNS를 Service Discovery로 사용한다.

```yaml
# infra/k8s/base/configmap.yaml
TRADING_SERVICE_URL: "http://trading-service:8083"
QUANT_ENGINE_URL:    "http://quant-engine:8082"

# infra/k8s/overlays/dev/kustomization.yaml (모놀리스 모드)
TRADING_SERVICE_URL: ""
QUANT_ENGINE_URL:    ""
```

`TradingServiceClient` / `QuantEngineClient`는 URL이 비어 있으면 로컬 서비스로 폴백하고, 설정되면 HTTP 호출로 위임한다 (Strangler-fig 패턴).

### API Gateway — NGINX Ingress 강화

NGINX Ingress에 다음 기능을 추가한다:
- 2계층 Rate Limiting: `60rps / IP`, burst×5, connections 20
- Request ID 전파: `X-Request-Id` 헤더 주입 (클라이언트 값 우선, 없으면 NGINX `$request_id`)
- 보안 헤더: `X-Content-Type-Options`, `X-Frame-Options`, `X-Powered-By` 제거
- CORS: `cors-allow-origin`, `cors-allow-headers` 명시적 제어
- WebSocket: `/ws` 경로 `Upgrade` / `Connection` 헤더 처리

## Reasons

### Service Discovery

- K8s DNS는 추가 인프라 없이 네임스페이스 내 서비스 이름으로 자동 해석된다.
- K8s Readiness Probe가 헬스 체크를 담당하므로 별도 헬스체크 메커니즘이 불필요하다.
- dev overlay에서 URL을 비워 모놀리스 모드로 동작시키면 Kafka·DB 없이 로컬 개발이 가능하다.
- Consul이나 Eureka를 추가하면 운영 복잡도가 높아지는 반면 이 규모에서 얻는 이점이 없다.

### API Gateway

- NGINX Ingress의 annotation으로 설정 가능한 범위가 현재 요구사항을 충족한다.
- Kong이나 Envoy 도입은 설정 복잡도와 학습 비용이 지금 단계에서 정당화되지 않는다.
- Request ID를 Gateway에서 주입하면 NGINX 로그와 Spring MDC 로그를 동일 ID로 연결할 수 있다.

## Consequences

- **K8s 의존**: Service Discovery는 K8s 환경에서만 동작한다. 로컬 Docker Compose 환경에서는 dev overlay로 모놀리스 모드를 사용한다.
- **NGINX annotation 한계**: 복잡한 라우팅 로직(A/B 테스트, 카나리 배포)은 NGINX annotation만으로 구현이 어렵다.
- **X-Forwarded-For 신뢰**: NGINX가 신뢰할 수 없는 환경에 노출되면 `X-Forwarded-For` 조작으로 Rate Limit을 우회할 수 있다. K8s Ingress 앞에 Cloud LB가 있다면 `real-ip` 설정으로 보완한다.

## Revisit When

- MSA 서비스가 3개 이상으로 늘어나 서비스 메시 수준의 트래픽 제어(재시도, 타임아웃, 서킷 브레이커)가 Ingress annotation으로 감당되지 않을 때 → Istio 또는 Linkerd 검토.
- 카나리 배포나 트래픽 분할이 필요해질 때 → NGINX Ingress의 `canary` annotation 또는 Argo Rollouts 검토.
