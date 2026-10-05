# ADR-064: 초기 기술 스택 선택 근거 (사후 재구성)

## Status
Accepted

이 문서는 2026-10-05에 작성했다. 첫 커밋 `2bac5c1`(LICENSE)에 이어 들어온 초기 커밋들(`a7e622e` 설계 문서,
`098febe` ADR-001~004, `dd55259` api/worker 골격, `a983fbf` web 골격, `d996f80` Docker Compose)과 당시 문서에
보이는 선택을 바탕으로, 저장소에 적혀 있지 않던 이유를 재구성한 것이다. 당시의 논의 기록은 없다. 아래 "재구성"으로 표시한
이유는 당시 문서가 직접 말하지 않은 추론이다.

아키텍처 스타일(ADR-001), 시계열 저장소(ADR-002), `stock_events` 중심 모델(ADR-003), 메시지 버스(ADR-004)의 근거는
이미 해당 ADR에 있으므로 여기서 반복하지 않는다. 이 ADR은 그 밖의 초기 선택(백엔드 언어, 프론트엔드, 실시간 전달, 로컬·배포 환경)을 다룬다.

## Context

초기 문서가 정한 제약:

- 팀 규모 1~2명 (ADR-001).
- MVP 핵심 화면은 "종목 상세 = 차트 + 이벤트 타임라인 + 관련 뉴스" (`docs/product.md`).
- 반응형 웹을 먼저 만들고 네이티브 앱(React Native / Expo)은 나중에 (`docs/architecture.md` Frontend).
- 백엔드 스택 표기는 "Java or Kotlin / Spring Boot / Modular Monolith / REST + WebSocket" (`docs/architecture.md` Backend).
- 배포 1단계는 "Single VM + Docker Compose", Kubernetes는 3단계 (`docs/architecture.md` Infra).

고려한 대안(재구성):

- 백엔드 언어: Java + Spring Boot / Kotlin + Spring Boot / TypeScript + Node.js 프레임워크
- 프론트엔드: Next.js / React + Vite SPA
- 차트: TradingView Lightweight Charts / 범용 차트 라이브러리
- 실시간 전달: REST 폴링 / Spring STOMP(simple broker) / 외부 STOMP 브로커 relay
- 실행 환경: Docker Compose / Kubernetes

## Decision

| 영역 | 선택 | 초기 커밋 |
|------|------|-----------|
| 백엔드 | Kotlin 1.9.25 + Spring Boot 3.5.0, JDK 17 toolchain, api와 worker 두 애플리케이션 | `dd55259` |
| 프론트엔드 | Next.js 15.1 + React 19 + TypeScript, TanStack Query, Zustand, Tailwind CSS | `a983fbf` |
| 차트 | Lightweight Charts 4.2 | `fd5480e` |
| 실시간 | STOMP over SockJS, `enableSimpleBroker("/topic")`. 웹은 우선 3초 REST 폴링 | `bdc136b`, `50dccb8` |
| 실행 환경 | Docker Compose: `timescale/timescaledb:latest-pg16`, `redis:7-alpine`, api/web은 `full` 프로필 | `d996f80` |

## Reasons

### 백엔드: Kotlin + Spring Boot

- Spring Boot 자체는 문서가 처음부터 정해 두었다. 모듈 경계를 패키지와 인터페이스로 나누는 ADR-001의 방식,
  JPA·Flyway·Spring Security·WebSocket이 모두 같은 생태계 안에 있다는 점이 1~2명 팀에 맞는다(재구성).
- Java 대신 Kotlin을 고른 이유는 문서에 없다(재구성). Kotlin 타입 시스템은 null이 될 수 있는 타입과 없는 타입을
  구분한다([Kotlin Null safety](https://kotlinlang.org/docs/null-safety.html)). 외부 API 응답처럼 값이 빠질 수 있는
  데이터를 많이 다루는 수집기에서 이 구분이 컴파일 시점에 드러난다. Spring Boot는 Kotlin을 공식 지원한다
  ([Spring Boot Kotlin Support](https://docs.spring.io/spring-boot/reference/features/kotlin.html)).
- TypeScript 백엔드는 프론트엔드와 언어를 맞출 수 있지만, ADR-002가 요구한 Flyway 마이그레이션과 위 Spring 생태계를
  다시 고르는 비용이 생긴다(재구성).

### 프론트엔드: Next.js

- Next.js는 "React framework for building full-stack web applications"이고 App Router는 Server Components 같은 최신
  React 기능을 지원한다([Next.js Docs](https://nextjs.org/docs)). 라우팅과 빌드가 한 프레임워크에 들어 있다.
- Vite는 빌드 도구이며 라우팅이나 SSR은 플러그인·다른 도구로 붙여야 한다([Vite Guide](https://vite.dev/guide/)).
  SPA로도 MVP 화면은 만들 수 있으므로 Next.js 선택의 이유는 "라우팅·빌드를 따로 고르지 않는다"는 편의다(재구성).
- 상태는 문서가 정한 대로 나눴다: 서버 상태는 TanStack Query, 실시간 상태는 Zustand (`docs/architecture.md`).
  TanStack Query 문서는 전통적인 상태 관리 라이브러리가 비동기·서버 상태에 약하다고 설명한다
  ([TanStack Query Overview](https://tanstack.com/query/latest/docs/framework/react/overview)).

### 차트: Lightweight Charts

- MVP의 핵심 화면이 캔들 차트 위 이벤트 마커다. Lightweight Charts는 금융 차트 전용 HTML5 라이브러리다
  ([lightweight-charts README](https://github.com/tradingview/lightweight-charts)). 범용 차트 라이브러리보다 캔들·시계열 축이
  기본으로 맞춰져 있다는 점을 택한 것으로 보인다(재구성).

### 실시간: STOMP simple broker, 그 전에는 폴링

- api 인스턴스가 하나인 동안은 Spring 내장 simple broker로 충분하다. Spring 문서는 simple broker가 STOMP 명령 일부만
  지원하고 클러스터링에 적합하지 않다고 적는다([Spring STOMP External Broker](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html)).
  외부 브로커(RabbitMQ 등) relay는 새 인프라를 하나 더 띄우는 일이라 1단계에서 미뤘다(재구성).
- 첫 웹 구현은 STOMP 연결 전 3초 REST 폴링으로 시작했다(`50dccb8`의 커밋 메시지: "REST polling fallback until STOMP is wired").

### 실행 환경: Docker Compose

- 문서의 1단계가 단일 VM + Docker Compose다. 로컬에서는 DB와 Redis만 띄우고, api/web은 `full` 프로필로 묶었다.
  Compose는 `profiles`가 없는 서비스만 기본으로 띄운다([Docker Compose profiles](https://docs.docker.com/compose/how-tos/profiles/)).
- Kubernetes는 문서상 3단계다. 서비스가 api·worker·web 셋인 동안은 오케스트레이터 운영 비용이 이득보다 크다(재구성).

## Consequences

- Kotlin 클래스는 기본이 final이라 Spring 프록시를 위해 `kotlin-spring` 플러그인이 필요하다
  ([Spring Boot Kotlin Support](https://docs.spring.io/spring-boot/reference/features/kotlin.html)). 골격의
  `kotlin("plugin.spring")`, `kotlin("plugin.jpa")`, `allOpen` 설정이 그 비용이다.
- api와 worker가 별도 Gradle 프로젝트라 도메인 타입(예: 틱 모델)을 공유하지 않고 각자 정의한다.
- simple broker는 api를 수평 확장하는 순간 한계가 된다. 각 인스턴스가 자기 구독자에게만 보낸다.
- Next.js 서버를 별도로 띄워야 하므로 정적 파일 호스팅보다 배포 단위가 하나 늘어난다.
- TimescaleDB 이미지를 `latest-pg16` 태그로 받으므로 이미지 갱신 시점에 따라 확장 버전이 달라질 수 있다.

## Revisit When

- api를 두 대 이상 띄워야 할 때(실시간 전달 경로 재설계).
- 네이티브 앱이 같은 API를 쓰기 시작해 웹 전용 가정이 깨질 때.
- 단일 VM의 자원으로 api·worker·DB·Redis를 함께 감당하지 못할 때.
