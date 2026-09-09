# ADR-033: Netty Broadcast Gateway 제거

## Status
Accepted

## Context

[ADR-005](005-kafka-go-gateway-netty-broadcast.md)가 `services/broadcast-gateway`(Netty 기반 커스텀 WebSocket 서버, `market.ticks`/`market.events`를 Kafka에서 직접 소비해 `ws://:9090/ws`로 클라이언트에 푸시)를 도입했다. ADR-005 자신이 이미 명시했듯 이건 "포트폴리오/학습 목적 아키텍처 변경이지 부하 대응이 아니다" — Kafka/Go/Netty를 다뤄본 적 없는 스택에 노출시키려는 의도였다.

[ADR-029](029-price-broadcast-pipeline.md)에서 실시간 시세 푸시 파이프라인을 복구하면서 이미 확인한 사실: **프론트엔드는 처음부터 지금까지 STOMP(`@stomp/stompjs`+`sockjs-client`, `/topic/stocks/{id}`)만 사용했고, broadcast-gateway용 클라이언트는 활성 코드든 죽은 코드든 단 한 번도 존재한 적이 없다.** ADR-029는 이 사실을 Consequences에 남기고 "결정을 미룬다"고 명시했었다.

이번에 [engineering-backlog.md](../engineering-backlog.md) §1의 첫 실행 가능 항목으로 이 결정을 내리기 전, 직접 다시 확인했다:

- **프론트엔드 참조 0건** — `apps/web` 소스 전체(빌드 산출물 제외)에서 포트 9090, `/ws` 커스텀 프레이밍, 이 프로토콜을 쓰는 코드가 활성/비활성 통틀어 전무하다.
- **CI 커버리지 0건** — `backend-ci.yml`의 빌드 매트릭스에 없고, 어떤 워크플로도 이 서비스를 빌드·테스트하지 않는다. `src/`에 테스트 파일 자체가 없다.
- **커밋 3건뿐** — 최초 구현 1건, 나머지 2건은 전부 기계적(gradle-wrapper jar 누락 수정, base 이미지 버전 bump)이다. "test"/"verify"/"fix" 성격의 커밋이 하나도 없다 — 작성 후 한 번도 실제로 검증된 적이 없다는 뜻이다.
- **인증·TLS·연결 제한 전무** — 자체 설계 문서(`docs/technical/netty-broadcast-gateway.md`, 이번에 함께 삭제)가 이미 "알려진 한계"로 명시하고 있었다.
- **Docker Compose `kafka` 프로파일에만** 연결돼 있어 기본 `dev.sh` 흐름과는 무관했다 — 즉 이걸 지워도 지금까지 아무 실제 워크플로도 깨지지 않는다.

ADR-005 자신의 "Revisit When" 첫 항목이 정확히 지금 상태를 가리킨다: *"두 새 서비스가 학습/시연 가치보다 운영 부담이 큰 경우"*. `PriceBroadcaster`/STOMP가 ADR-029로 이미 실제 운영 경로가 됐으므로, 학습 가치는 이미 회수됐고 남은 건 미검증·무인증 상태로 방치된 코드 하나뿐이다.

## Decision

`services/broadcast-gateway` 전체를 삭제한다. 채택(프론트엔드를 이쪽으로 전환)은 고려하지 않는다 — STOMP가 이미 실제로 동작하고 이번 세션 내내 라이브 검증까지 마쳤는데, 검증된 적 없는 대안으로 갈아탈 이유가 없다.

구체적으로:
1. `services/broadcast-gateway/` 디렉터리 전체 삭제 (`rm -rf`, 다른 어떤 빌드 파일도 이 디렉터리를 참조하지 않아 이것만으로 충분).
2. `docker-compose.yml`의 `broadcast-gateway` 서비스 정의 및 관련 주석 제거.
3. `docs/technical/netty-broadcast-gateway.md`(설계 문서) 삭제 — 더 이상 존재하지 않는 것의 구현 딥다이브를 남겨두면 이 세션이 여러 번 지적한 "코드보다 앞서가는 문서" 문제를 그대로 재현한다.
4. `docs/architecture.md`(다이어그램, Service Ports 표, Kafka Topics 표), `docs/technical/kafka-tick-pipeline.md`, `docs/technical/README.md`에서 참조 제거.
5. ADR-005 본문은 그대로 두고(당시엔 정확한 기록이었다) Status 아래 짧은 갱신 노트만 추가 — Kafka·Go market-gateway(ADR-005의 나머지 결정)는 이 ADR과 무관하게 그대로 유지된다.

## Reasons

- **검증 안 된 대안으로 갈아탈 이유가 없다**: STOMP 경로는 이번 세션에 실제 브라우저로 실시간 가격 갱신까지 확인했다(ADR-029). Netty 경로는 로컬 빌드 1회 외엔 실행 검증 기록이 없다 — 프로덕션 트랙(CLAUDE.md 명시)에서 두 실시간 전송 경로 중 하나를 고르라면 검증된 쪽이 자명하다.
- **죽은 코드를 "나중에 채택할 수도 있다"는 이유로 남겨두지 않는다**: CLAUDE.md는 가상의 미래 요구사항을 위한 설계를 하지 말라고 명시한다. 지금 이 코드를 유지하는 유일한 근거는 "언젠가 부하가 커지면 필요할 수도"인데, ADR-005 스스로 이게 부하 대응이 아니라고 못 박았고, 실제 부하 문제가 생기면 그때 다시 설계하는 게 지금 미검증 코드를 계속 끌고 가는 것보다 싸다.
- **문서를 코드와 다시 일치시킨다**: `docs/architecture.md`가 이 서비스를 마치 살아있는 구성요소처럼 다이어그램·표에 실어두고 있었다 — 이번 세션 초반부터 반복 지적된 "문서가 실제 구현을 과장한다" 패턴의 또 다른 사례였다. 삭제와 동시에 문서도 고친다.

## Consequences

- 실시간 전송 경로가 STOMP 하나로 정리된다 — ADR-029가 이미 사실상 유일한 경로로 만들어뒀던 걸 문서·인프라 정의까지 공식화하는 것뿐이다.
- 포트 9090(`BROADCAST_GW_PORT`)이 비게 된다. `docker-compose.yml`의 다른 서비스(Prometheus, 호스트 포트 9091로 이미 우회돼 있었다)가 원래 이 충돌을 피하려던 것이었는데, 원복하지 않고 그대로 뒀다 — 이미 동작하는 설정을 이유 없이 건드리지 않는다.
- Kafka·Go `market-gateway`는 전혀 영향받지 않는다 — `ingestion.source=kafka` 모드는 여전히 동작하고, `TickKafkaConsumer`(worker)와 `MarketTickBroadcastConsumer`(api)는 이 ADR과 무관하게 그대로다.
- "Kafka/Go/Netty를 다뤄본다"는 ADR-005의 원래 학습 목표 중 Netty 부분은 더 이상 이 저장소에 남지 않는다 — Kafka·Go 부분은 여전히 살아있고 실제로 쓰이는 중이다.

## Revisit When

- 정말로 프론트엔드 연결 수가 Spring STOMP의 처리량을 넘어서는 문제가 실측으로 확인될 때 — 그때는 지금 지운 코드를 복구하기보다, 그 시점의 실제 병목(연결 수? 메시지 빈도? 직렬화 비용?)에 맞춰 새로 설계하는 게 낫다. git 히스토리에 원본이 남아있으니 참고 자료로는 여전히 쓸 수 있다.
