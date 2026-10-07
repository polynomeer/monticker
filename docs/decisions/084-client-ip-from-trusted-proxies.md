# ADR-084: 클라이언트 IP는 신뢰 프록시 목록으로 X-Forwarded-For를 오른쪽부터 읽어 정한다

## Status
Accepted

## Context

`RateLimitFilter`(IP 기반 전역 레이트리밋 — 로그인 10회/분, 가입 5회/10분 등)는 `X-Forwarded-For`의 **첫 값**을 클라이언트 IP로 썼다.
첫 값은 클라이언트가 보낸 그대로다: ingress-nginx(`infra/k8s/base/ingress.yaml`)는 `$proxy_add_x_forwarded_for`로 받은 헤더 **뒤에**
자기가 본 `$remote_addr`를 덧붙이기 때문이다. 그래서

- 요청마다 `X-Forwarded-For: <랜덤>`을 붙이면 로그인 브루트포스의 IP 한도가 사라지고,
- `X-Forwarded-For: <피해자 IP>`로 남의 버킷을 소진시켜 그 사용자를 429로 막을 수 있었다.

`AuditAspect`는 반대로 `remoteAddr`만 써서, 프록시 뒤에서는 감사 로그의 IP가 전부 ingress 파드 IP였다.
`RateLimitedAspect`는 userId 기반이라 IP를 쓰지 않는다. 동의 기록·로그인 이력에는 IP 컬럼이 없다.

배포 토폴로지: 클라이언트 → (클라우드 L4 LB) → ingress-nginx 컨트롤러 → api 파드. `/api`는 ingress가 api 서비스로 직접 보낸다
(Next.js `rewrites()`는 로컬 개발에서만 `/api`를 프록시한다 — `localhost:3000 → localhost:8080`, 피어는 127.0.0.1).
`infra/docker/nginx/nginx.conf`도 같은 1홉 구조다.

고려한 대안:

- **A. `server.forward-headers-strategy=NATIVE` + Tomcat `RemoteIpValve`의 `internal-proxies`** — 표준이고 `remoteAddr` 자체가 바뀌어
  모든 코드가 공짜로 고쳐진다. 그러나 (1) 기본 `internal-proxies`가 정규식이고 사설 대역 전체를 이미 믿는다 — CIDR 목록 설정이 어색하고
  실수하기 쉽다. (2) `X-Forwarded-Proto/Host/Port`도 함께 적용돼 OAuth2 리다이렉트 URI·쿠키 Secure 판정 등 이번 결함과 무관한 동작이
  같이 바뀐다. (3) 단위 테스트로 스푸핑 시나리오를 고정하기 어렵다.
- **B. 애플리케이션 레벨 `ClientIpResolver`** — 채택.

## Decision

- `common/http/ClientIpResolver` 하나가 클라이언트 IP를 정한다. 호출부: `RateLimitFilter`, `AuditAspect`.
  - 기본은 `request.remoteAddr`.
  - 직전 피어가 `app.http.trusted-proxies`(쉼표 구분 CIDR/주소)에 속할 때만 `X-Forwarded-For`를 읽는다(여러 헤더 줄은 순서대로 이어 붙임).
  - **오른쪽부터** 신뢰 프록시를 건너뛰고 처음 나오는 신뢰하지 않는 주소를 반환한다. 전부 신뢰 프록시면 가장 먼 홉.
  - 깨진 값(호스트명, `ip:port`, 빈 항목, 범위 밖 옥텟)을 만나면 예외 없이 `remoteAddr`로 돌아간다. 주소는 리터럴로만 해석한다(DNS 조회 없음).
    IPv6·대괄호 표기·IPv4-mapped IPv6를 지원하고 결과는 정규화된 표기로 낸다.
  - 설정의 잘못된 CIDR은 기동 실패(`IllegalArgumentException`)로 드러낸다.
- `X-Real-IP`는 읽지 않는다(신뢰 홉이 덧붙인 XFF 마지막 값과 같은 정보이고, 별도 헤더를 믿으면 신뢰 경계가 둘이 된다).
- 기본값은 빈 목록(헤더를 전혀 믿지 않음). 로컬(`scripts/dev/up.sh`, Next.js dev 프록시)은 설정 없이 안전하다.
- 운영: `infra/k8s/base/configmap.yaml`의 `APP_HTTP_TRUSTED_PROXIES`(= `app.http.trusted-proxies`). base 값은 RFC1918 전체
  `10.0.0.0/8,172.16.0.0/12,192.168.0.0/16`이고, 실배포 전에 클러스터의 ingress-nginx 파드 CIDR로 좁힌다.

## Reasons

- 신뢰 경계가 설정 한 줄(CIDR 목록)로 명시되고, 빈 기본값이 안전 쪽이다.
- 오른쪽부터 읽는 방식은 클라이언트가 왼쪽에 무엇을 써 넣든 결과에 영향을 못 준다 — ingress 앞에 L7 LB가 하나 더 붙어도 CIDR만 추가하면 된다.
- 프로토콜/호스트 헤더 처리(OAuth2 리다이렉트 등)는 건드리지 않아 변경 범위가 레이트리밋·감사 로그로 한정된다.
- 스푸핑·다중 홉·깨진 값·IPv6를 순수 단위 테스트로 고정할 수 있다.

## Consequences

- 운영에서 `APP_HTTP_TRUSTED_PROXIES`가 비어 있으면 모든 요청이 ingress 파드 IP 하나로 보여 IP 버킷을 전 사용자가 공유한다(전면 429).
  그래서 base ConfigMap에 값을 넣었다 — ConfigMap을 덮어쓰는 오버레이는 이 키를 빠뜨리면 안 된다.
- base의 RFC1918 값은 클러스터 내부의 어떤 파드든 XFF를 위조할 수 있게 한다(클러스터 내부는 신뢰 영역으로 본다). 좁히는 것이 원칙.
- 클라우드 L4 LB가 `externalTrafficPolicy: Cluster`로 SNAT하면 ingress가 보는 `$remote_addr`부터 노드 IP라 이 결정으로도 클라이언트 IP를
  알 수 없다. 그 경우 LB의 `externalTrafficPolicy: Local` 또는 PROXY protocol(ingress-nginx `use-proxy-protocol`)이 필요하다 — 인프라 몫.
- `request.remoteAddr`를 직접 읽는 새 코드는 이 규칙을 우회한다. 클라이언트 IP가 필요하면 `ClientIpResolver`를 주입해 쓴다.
- IPv6 클라이언트는 /64 안에서 주소를 바꿔 IP 한도를 분산할 수 있다(기존과 동일). 로그인은 이메일 단위 실패 잠금(AuthService)이 보완한다.

## Revisit When

- ingress 앞에 CDN/L7 LB(Cloudflare, ALB 등)를 붙일 때 — 그 대역을 신뢰 목록에 추가하거나, CDN 전용 헤더(`CF-Connecting-IP`) 사용을 검토한다.
- 프로토콜/호스트 포워딩(`X-Forwarded-Proto`)도 앱이 알아야 하는 요구가 생길 때 — 대안 A로 통합을 다시 본다.
- IPv6 트래픽 비중이 커질 때 — 레이트리밋 키를 /64 프리픽스로 묶는 것을 검토한다.
