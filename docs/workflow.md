# monticker — Development Workflow

> Read this when: starting a new feature, writing a prompt for Claude, setting up subagents/hooks, or managing PRs and CI.

## Development Flow

```
Write spec / issue
  → Plan with Claude (/plan)
  → Review plan
  → Implement
  → Run tests / lint / build
  → Code review (/code-review, /security-review)
  → Open PR
  → GitHub Actions CI + Claude review
  → Merge
```

---

## Repository Layout

```
monticker/
├── apps/
│   ├── web/          # Next.js
│   └── mobile/       # React Native / Expo (added later)
├── backend/
│   ├── api/          # Spring Boot API
│   └── worker/       # Spring Boot async workers
├── packages/
│   ├── types/        # Shared TypeScript types
│   └── api-client/   # Shared API client
├── infra/
│   ├── docker/
│   └── nginx/
├── docs/             # Product, architecture, decisions
├── .claude/
│   ├── agents/       # Subagent definitions
│   └── settings.json
├── .github/
│   ├── workflows/
│   └── ISSUE_TEMPLATE/
├── CLAUDE.md
├── .mcp.json
├── docker-compose.yml
└── Makefile
```

---

## CLAUDE.md Sections to Maintain

Keep `CLAUDE.md` updated as the single source of truth for Claude in this project. It must always contain:

- Product summary and core principle (`stock_events` is central)
- Architecture principle (event-centric, modular monolith)
- Repository layout
- Backend rules (Spring Boot, module boundaries, DB rules)
- Frontend rules (TypeScript, Next.js, TanStack Query, Zustand)
- Security rules (no .env access, no real order execution)
- Development rules (plan → implement → test cycle)
- Build and run commands for all apps

---

## Daily Workflow

```bash
cd monticker
git status
claude
```

First prompt in each session:
```
Read the current branch status and recent changes.
Summarize what is in progress and suggest the next 3 tasks.
Do not modify any files yet.
```

Always follow: **read → plan → implement**. Never start with "implement X" directly.

---

## Feature Development

Break every feature into issues that can be completed in one day or less.

Example: Stock Detail screen → 8 issues

```
Issue 1: stocks table migration
Issue 2: stock search API
Issue 3: current price Redis cache API
Issue 4: 1m candle API
Issue 5: stock_events table migration
Issue 6: stock detail page layout (web)
Issue 7: event markers on chart
Issue 8: WebSocket price connection
```

### Prompt pattern for planning

```
/plan Implement [feature name].

Scope:
- [affected module 1]
- [affected module 2]

Requirements:
- [specific requirement]

Constraints:
- Do not touch [out-of-scope module].
- Do not put business logic in controllers.
- Propose file list and order before implementing.
```

### Prompt pattern for implementation

```
Proceed with the plan above.

After implementation:
1. Show the migration file.
2. List all changed API endpoints.
3. Run the tests.
4. If any test fails, analyze and fix it.
```

### After implementation

```
/diff
/code-review high
/security-review
```

---

## Subagents

Place subagent definitions in `.claude/agents/`. Each agent focuses on one domain.

### Recommended agents

| File | Purpose |
|------|---------|
| `backend-architect.md` | Module boundaries, DB design, API contracts |
| `market-data-engineer.md` | Tick collection, candle aggregation, Redis pipeline |
| `event-detector-reviewer.md` | Price spike, volume surge, event scoring logic |
| `frontend-reviewer.md` | Next.js component structure, state management |
| `security-reviewer.md` | Auth, input validation, secret handling |
| `test-engineer.md` | Test coverage gaps, test quality |

### Example: `backend-architect.md`

```markdown
---
name: backend-architect
description: Use proactively when designing or modifying Spring Boot modules, DB schema, domain boundaries, or worker architecture.
tools: Read, Grep, Glob, Bash
model: sonnet
---

You are the backend architect for monticker.

Principles:
- Modular monolith first. No microservices unless explicitly requested.
- stock_events is the central event model.
- PostgreSQL for business data. TimescaleDB for ticks and candles.
- Redis for latest price, pub/sub, streams, cooldown.
- Controllers must be thin. Business logic belongs in application/domain services.

Return for each review:
1. Architectural risk
2. Boundary violation
3. Data model concern
4. Test gap
5. Suggested correction
```

### Example: `event-detector-reviewer.md`

```markdown
---
name: event-detector-reviewer
description: Use proactively when implementing price spike, volume surge, event scoring, or alert trigger logic.
tools: Read, Grep, Glob, Bash
model: sonnet
---

You are the event detection reviewer for monticker.

Rules:
- Reject naive fixed thresholds. Require stock-specific historical baselines.
- Event generation must be idempotent.
- Time must be injectable for deterministic tests.

Return:
1. Logic correctness
2. False positive risk
3. Data requirements
4. Test cases needed
5. Suggested implementation improvement
```

---

## Hooks

Configure in `.claude/settings.json`.

### Auto-format after file edit

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [{ "type": "command", "command": "./scripts/claude-after-edit.sh" }]
      }
    ]
  }
}
```

`scripts/claude-after-edit.sh`:
```bash
#!/usr/bin/env bash
set -e
if git diff --name-only | grep -E '\.(ts|tsx|js|jsx|json|md)$' >/dev/null; then
  pnpm format || true
fi
if git diff --name-only | grep -E '\.(kt|java)$' >/dev/null; then
  ./gradlew spotlessApply || true
fi
```

### Block sensitive file edits

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [{ "type": "command", "command": "./scripts/claude-protect-files.sh" }]
      }
    ]
  }
}
```

`scripts/claude-protect-files.sh`:
```bash
#!/usr/bin/env bash
FILE_PATH="$(cat | jq -r '.tool_input.file_path // empty')"
case "$FILE_PATH" in
  *.env|*.env.*|*secrets*|*credentials*)
    echo "Blocked: Claude cannot edit $FILE_PATH" >&2
    exit 2 ;;
esac
exit 0
```

---

## `.claude/settings.json` — Permissions

```json
{
  "$schema": "https://json.schemastore.org/claude-code-settings.json",
  "permissions": {
    "allow": [
      "Bash(git status)",
      "Bash(git diff *)",
      "Bash(git branch *)",
      "Bash(find . -maxdepth *)",
      "Bash(ls *)",
      "Bash(pnpm lint)",
      "Bash(pnpm test)",
      "Bash(pnpm build)",
      "Bash(./gradlew test)",
      "Bash(./gradlew build)"
    ],
    "deny": [
      "Read(./.env)",
      "Read(./.env.*)",
      "Read(./secrets/**)",
      "Bash(rm -rf /)",
      "Bash(curl * | sh)",
      "Bash(curl * | bash)"
    ]
  }
}
```

---

## MCP Integrations

Recommended priority:

| Priority | MCP | Use |
|----------|-----|-----|
| 1 | GitHub | Read issues, post PR comments |
| 2 | Playwright | Verify web UI, E2E |
| 3 | PostgreSQL (dev only) | Inspect schema, test queries |
| 4 | Sentry | Fix errors from stack traces |
| 5 | Figma | Implement from design |

---

## GitHub Integration

Install the Claude Code GitHub App:
```
/install-github-app
```

Issue comment pattern:
```
@claude Implement watchlist group API.

Requirements:
- Add Flyway migration for watchlist_groups and watchlist_items
- Add REST endpoints
- Add service-layer tests
- Follow CLAUDE.md
- Do not touch mobile app
```

PR review pattern:
```
@claude Review this PR for:
- module boundary violations
- missing tests
- unsafe database migration
- inconsistent API response schema
```

---

## CI/CD Structure

```
.github/workflows/
├── backend-ci.yml    # triggered on backend/** changes
├── web-ci.yml        # triggered on apps/web/** and packages/** changes
├── e2e-ci.yml         # triggered on backend/api/**, apps/web/**, packages/** changes
├── mobile-ci.yml     # triggered on apps/mobile/** changes
└── pr-review.yml     # Claude review comment on every PR
```

Every feature must ship with both a unit test and, when it crosses a real
boundary (DB, Redis, another service, the browser), an integration test.
Mocking the boundary you're supposed to be testing defeats the point — see
`test-engineer.md` and the incident below.

| Layer | Where | Runs against | Command |
|-------|-------|---------------|---------|
| Unit | `backend/*/src/test` | Mocks (MockK), no Spring context | `./gradlew test` |
| Integration (backend) | `backend/*/src/integrationTest` | Real Postgres/Redis via **Testcontainers** — never H2, never mocked | `./gradlew integrationTest` |
| Unit (web) | `apps/web/src/test` | Vitest + Testing Library, mocked `fetch` | `pnpm test` |
| Integration (e2e, web) | `apps/web/e2e` | Real Next.js server + real API, driven by **Playwright** | `pnpm test:e2e` |

Backend CI runs `./gradlew test` then `./gradlew integrationTest` per module (api,
worker, trading-service, quant-engine) — each module has an `integrationTest`
Gradle source set wired for Testcontainers, even before it has tests in it.
Web CI runs `pnpm lint`, `pnpm test`, `pnpm build`. e2e-ci boots the real API
(Postgres + Redis service containers) and the real Next.js server together and
runs Playwright against them — this is the only layer that catches bugs that
only exist where the two apps actually meet (e.g. a CSP header blocking the
API's WebSocket handshake).

> **Why this exists**: a Redis cache serializer bug shipped because every
> existing test mocked Redis, so nothing ever exercised the real
> serialize→deserialize round trip. It surfaced as intermittent 500s on
> screener/regime/pattern/portfolio-optimizer in production use. Fixed in
> `CacheConfig.kt`, with `CacheConfigIntegrationTest` (real Testcontainers
> Redis) added specifically so this class of bug fails CI instead of shipping.

---

## Issue Template

`.github/ISSUE_TEMPLATE/feature.yml`:
```yaml
name: Feature
title: "[Feature] "
labels: ["feature"]
body:
  - type: textarea
    id: goal
    attributes:
      label: Goal
    validations:
      required: true
  - type: textarea
    id: scope
    attributes:
      label: Scope
      placeholder: |
        - backend/api
        - apps/web
    validations:
      required: true
  - type: textarea
    id: requirements
    attributes:
      label: Requirements
    validations:
      required: true
  - type: textarea
    id: constraints
    attributes:
      label: Constraints
  - type: textarea
    id: test
    attributes:
      label: Test Plan
```

---

## PR Template

`.github/pull_request_template.md`:
```markdown
## Summary

## Scope
- [ ] backend/api
- [ ] backend/worker
- [ ] apps/web
- [ ] apps/mobile
- [ ] packages
- [ ] infra
- [ ] docs

## Changes

## Test Plan
- [ ] Unit tests
- [ ] Integration tests
- [ ] Manual verification
- [ ] Build / Lint

## Risks

## Follow-up
```

PR body generation:
```
Write a PR description based on the current git diff.

Include: summary, changed modules, key design decisions, test results, risks, follow-up.
Do not exaggerate. Only describe what actually changed.
```

---

## What Claude Should Handle vs. What You Decide

### Delegate to Claude

```
Boilerplate and scaffolding
Migration drafts
DTO / API client code
Test case generation
Repetitive CRUD
React page skeletons
Error message wording
README / ADR drafts
PR descriptions
Refactoring candidates
Missing test detection
```

### You decide

```
Product direction
Financial information tone and limits
Data provider selection
Architecture boundaries
Event detection algorithm thresholds
Privacy / security / terms
Whether to add real order execution
```

monticker is a financial information app. Add to `CLAUDE.md`:
> Never generate buy/sell recommendations, price targets, or return guarantees.

---

## Week-by-Week Roadmap

| Week | Goal |
|------|------|
| 1 | Monorepo init, CLAUDE.md, Docker Compose, backend/api skeleton, apps/web skeleton, GitHub Actions |
| 2 | Stock Module, Watchlist Module, basic search UI, watchlist UI |
| 3 | TimescaleDB schema, Mock Market Data Worker, Redis price cache, current price API, WebSocket basic |
| 4 | stock_events, Volume Surge Detector, Price Spike Detector, event timeline API |
| 5 | Web chart, event markers, news placeholder, Alert Rule basics |
| 6 | Expo mobile skeleton, watchlist screen, push token registration, alert worker draft |
| 7 | Claude GitHub Actions, refine subagents and hooks, expand test coverage |
| 8 | MVP demo, README polish, architecture docs, portfolio write-up |

---

## 7 Rules for Using Claude Code on monticker

```
1. Always /plan before "implement".
2. Keep each task to one day or less.
3. Update CLAUDE.md continuously.
4. All features follow: Issue → Branch → PR → Review → Merge.
5. Every Claude-generated change goes through /diff and /code-review.
6. Block .env and secrets via settings.json and hooks.
7. You own product direction and architecture. Claude owns repetitive implementation.
```
