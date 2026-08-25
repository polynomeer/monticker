# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**monticker** — an MIT-licensed project by augboot. The repository is currently in its initial state with no source code committed yet.

Once development begins, update this file with:
- Build, lint, and test commands
- Architecture overview and key entry points
- Any non-obvious conventions or constraints

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

- [docs/product.md](docs/product.md) — product identity, feature axes, MVP scope, key design decisions
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
