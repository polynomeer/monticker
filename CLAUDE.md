# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**monticker** — an MIT-licensed event-centric stock observation platform with a built-in quant strategy lab and investment wallet, by augboot. See [docs/product.md](docs/product.md) for product identity and full service structure, [docs/architecture.md](docs/architecture.md) for module boundaries and infra.

**Product stage: MVP is complete. The project is now in active commercialization** ([ADR-023](docs/decisions/023-commercialization-pivot.md)) — real brokerage order execution (BYOK model via KIS/Toss Securities Open API), Quant Lab UI, Strategy Market, and guarded AI-assisted trading are in scope going forward, not excluded. Treat every new feature and refactor as production-track: real user money and real broker credentials will eventually be in play, so security, compliance, and concurrency-safety bars apply from the start, not as a later hardening pass.

## Architecture Decision Records (ADRs)

**ADR을 언제 작성하는가**

아래 기준 중 하나라도 해당하면 구현 전 또는 구현 직후 ADR을 작성한다:

- 두 가지 이상의 설계 방식을 고려하고 하나를 선택했을 때
- 기존 결정을 번복하거나 크게 수정했을 때
- 외부 시스템(PG, 브로커, AI 등) 연동 방식을 결정했을 때
- 도메인 모델의 핵심 구조(이벤트 소싱, CQRS, Saga 등)를 채택했을 때
- 비기능 요건(성능, 보안, 비용)이 설계에 영향을 줬을 때

**ADR 형식** (`docs/decisions/NNN-kebab-title.md`)

```markdown
# ADR-NNN: 제목

## Status
Accepted | Deprecated | Superseded by ADR-NNN

## Context
왜 이 결정이 필요했는가. 고려한 대안들.

## Decision
무엇을 선택했는가. 핵심 설계를 코드/다이어그램으로 보여줘도 됨.

## Reasons
왜 이 방식을 선택했는가. 대안 대비 장점.

## Consequences
이 결정의 트레이드오프. 단점, 추가 복잡성, 유지보수 부담.

## Revisit When
언제 이 결정을 재검토해야 하는가.
```

**번호 규칙**: 기존 최대 번호 + 1. 한 PR에 여러 결정이 있으면 각각 별도 파일.

**ADR 위반 금지**: 기존 ADR의 결정을 번복할 때는 반드시 새 ADR을 작성하고 기존 ADR의 Status를 `Superseded by ADR-NNN`으로 업데이트한다.

---

## Reference Docs

- [docs/README.md](docs/README.md) — 독자별·주제별 전체 문서 색인. 새 문서를 추가하면 여기에도 등록한다
- [CONTRIBUTING.md](CONTRIBUTING.md) — 로컬 환경, 커밋/PR 규칙, 영역별 주의사항 (사람·에이전트 공통)
- [docs/product.md](docs/product.md) — product identity, feature axes, product scope, key design decisions
- [docs/resilience-plan.md](docs/resilience-plan.md) — 장애 시나리오별 대응 능력 판정(현재 P0 결함 8건), 모니터링·부하테스트·카오스테스트 설계
- [docs/security-review.md](docs/security-review.md) — 시큐어 코딩/보안 설계 점검: Critical 3건(하드코딩된 JWT·암호화 키가 실제로 라이브에 올라가는 배포 경로 공백, 토큰 localStorage 저장, 실브로커 주문 API 입력검증 부재), 우선순위별 개선방안
- [docs/validation-hardening-plan.md](docs/validation-hardening-plan.md) — 입력값·논리분기 검증 점검: 정상 사용자의 잘못된 입력·놓친 분기 관점(실주문이 검증보다 먼저 브로커 호출, 수량>0/정수 검증 누락, 리스크 게이트 우회, 옵티마이저/리밸런싱 로직 결함), P0~P2 작업계획. security-review.md의 C3/H2를 보완
- [docs/scale-out-plan.md](docs/scale-out-plan.md) — 대규모 트래픽·데이터 대응 아키텍처 전환 계획: 병목 인벤토리, 목표 아키텍처, Phase 0~4 로드맵
- [docs/launch-plan.md](docs/launch-plan.md) — commercial launch checklist: tech debt gates, legal/compliance, security, infra, phased rollout
- [docs/legal-review-brief.md](docs/legal-review-brief.md) — briefing pack (facts + question list, not legal advice) to hand an actual lawyer for Phase 1 sign-off
- [docs/architecture.md](docs/architecture.md) — system architecture, tech stack, module boundaries, API design
- [docs/workflow.md](docs/workflow.md) — Claude Code development workflow, subagents, hooks, CI/CD
- [docs/data-model.md](docs/data-model.md) — full DB schema (PostgreSQL, TimescaleDB, Redis key conventions)
- [docs/external-apis.md](docs/external-apis.md) — stock price, news, disclosure, AI provider candidates and setup
- [docs/elasticsearch.md](docs/elasticsearch.md) — ES 인덱스 6개·도메인 8개 적용 현황, 파이프라인, fallback 전략
- [docs/decisions/](docs/decisions/) — Architecture Decision Records (ADRs)
- [docs/technical/](docs/technical/README.md) — implementation deep-dives (how it was built)
- [docs/domain/](docs/domain/README.md) — product/business rationale (why it was designed this way)
- [docs/manual/](docs/manual/README.md) — end-user manual (how to use each screen)

## Commit Convention

Follow the [Conventional Commits](https://www.conventionalcommits.org/) specification.

### Format

```
<type>(<scope>): <subject>

[optional body]

[optional footer(s)]
```

### Types

| Type       | When to use                                              |
|------------|----------------------------------------------------------|
| `feat`     | A new feature                                            |
| `fix`      | A bug fix                                                |
| `docs`     | Documentation changes only                               |
| `style`    | Formatting, missing semicolons, etc. (no logic change)   |
| `refactor` | Code change that is neither a fix nor a feature          |
| `perf`     | Performance improvements                                 |
| `test`     | Adding or updating tests                                 |
| `chore`    | Build process, dependency updates, tooling               |
| `ci`       | CI/CD configuration changes                              |
| `revert`   | Reverts a previous commit                                |

### Scopes

Map scope to the repository structure:

| Scope | Target |
|-------|--------|
| `api` | backend/api |
| `worker` | backend/worker |
| `web` | apps/web |
| `mobile` | apps/mobile |
| `types` | packages/types |
| `infra` | infra/ |
| `ci` | .github/workflows/ |
| `docs` | docs/ |

If a commit touches multiple scopes, split it into separate commits.

### Rules

- **Subject**: imperative mood, lowercase, no trailing period, ≤ 72 characters
  - Good: `feat(auth): add JWT refresh token support`
  - Bad: `Added JWT refresh token.`
- **Scope**: optional, lowercase noun describing the area changed (e.g. `api`, `ui`, `auth`)
- **Body**: wrap at 72 characters; explain *what* and *why*, not *how*
- **Breaking changes**: add `!` after the type/scope (`feat!:`) and a `BREAKING CHANGE:` footer
- **Co-authorship**: append `Co-Authored-By: Name <email>` in the footer when applicable

### Examples

```
feat(ticker): add real-time price streaming via WebSocket

fix(ui): correct overflow clipping on mobile ticker cards

docs: update README with environment variable reference

chore(deps): upgrade Go to 1.23.0

feat!: replace REST polling with WebSocket API

BREAKING CHANGE: clients must now connect via ws:// instead of polling /api/prices
```
