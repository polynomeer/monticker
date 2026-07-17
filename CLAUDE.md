# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**monticker** — an MIT-licensed project by augboot. The repository is currently in its initial state with no source code committed yet.

Once development begins, update this file with:
- Build, lint, and test commands
- Architecture overview and key entry points
- Any non-obvious conventions or constraints

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
