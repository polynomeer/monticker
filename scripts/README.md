# scripts/

저장소 루트에서 실행한다. 자주 쓰는 것은 `make` 타깃으로도 부를 수 있다.

## 로컬 개발 — `scripts/dev/`

| 스크립트 | make | 하는 일 |
|---|---|---|
| `dev/up.sh [--kafka\|--msa\|--pinpoint]` | `make dev` · `dev-kafka` · `dev-msa` | Docker 인프라 → API → Worker → Web 순서로 띄우고 헬스체크를 기다린다. Ctrl-C로 전부 정리 |
| `dev/down.sh [--apps-only\|--volumes]` | `make dev-down` | 남은 앱 프로세스(고아 bootRun JVM 포함)와 인프라를 정리. `--volumes`는 로컬 데이터 삭제(확인을 묻는다) |
| `dev/status.sh` | `make dev-status` | 인프라 컨테이너(OOM 종료 표시), 앱 헬스·readiness, Docker 메모리 상위 컨테이너 |
| `dev/doctor.sh` | `make doctor` | 사전 점검: java·node·pnpm·docker, Docker VM 메모리 여유 |
| `dev/lib.sh` | — | 위 스크립트 공용(직접 실행하지 않는다) |

**모드**

| | 시세 경로 | 비고 |
|---|---|---|
| (기본) | MockPriceGenerator(워커 내부) | Kafka가 없어 Kafka를 거치는 기능(검색 색인 ADR-042, 사용자 알림 ADR-065)은 동작하지 않는다 |
| `--kafka` | Go market-gateway → Kafka → Worker | 호스트 앱은 Kafka EXTERNAL 리스너(`localhost:29092`)로 붙는다 |
| `--msa` | `--kafka` + 워커 컨테이너 3개(market/event/alert) | 호스트 워커는 띄우지 않는다(띄우면 시세 생산이 중복된다) |

**포트** — 선호 포트(5432·8080·8081·3000 …)가 다른 프로젝트에 점유돼 있으면 다음 빈 포트로 우회하고 알린다. 이미 떠 있는 이
프로젝트의 compose 컨테이너는 그 포트를 그대로 재사용한다. 실제 쓴 포트는 `logs/.dev-state`에 남고 `down.sh`·`status.sh`가 읽는다.
이 저장소의 프로세스가 아니면 절대 죽이지 않는다.

**로그** — `logs/api.log` · `logs/worker.log` · `logs/web.log` (실행마다 덮어쓴다). 기동 실패 시 마지막 30줄을 출력한다.

**문제가 생기면**
- `elasticsearch 시작 타임아웃` / 컨테이너 exit 137 → Docker VM 메모리 부족(OOM). `make dev-status`로 메모리를 많이 쓰는 컨테이너를
  확인하고 멈추거나, Docker Desktop → Settings → Resources → Memory를 늘린다. `up.sh`도 기동 전에 경고한다.
- 터미널을 닫아 앱이 남았다 → `make dev-down` (또는 다음 `up.sh`가 이전 실행의 잔여물을 정리한다).

## 검사 — `scripts/check.sh`

```bash
scripts/check.sh              # api·worker 단위 테스트 + web audit·lint·test
scripts/check.sh web          # 대상만
scripts/check.sh --full       # CI와 같게: + integrationTest(Docker 필요) + web build
```

실패해도 끝까지 돌고 요약한다. 명령은 `.github/workflows/backend-ci.yml`·`web-ci.yml`과 같다 — 워크플로를 바꾸면 여기도 맞출 것.

## 데이터 — `scripts/data/`

| 스크립트 | 하는 일 |
|---|---|
| `data/backfill-candles.py` | Yahoo Finance 일봉을 `candles_1d`(KST 자정 버킷)와 `candles_1m`(09:00 KST 대표값)에 적재. 처음 실행하거나 DB를 초기화한 뒤 |

```bash
pip install -r scripts/data/requirements.txt
python3 scripts/data/backfill-candles.py                 # 활성 종목 전체, 1년치
python3 scripts/data/backfill-candles.py --symbols 005930 AAPL --from 2024-01-01
```

DB는 `--db-url` → `$DATABASE_URL` → 실행 중인 compose postgres의 실제 포트 순으로 고른다. 다운로드는 `data/backfill/`에 캐시된다.

## CI·부하 테스트

| 경로 | 하는 일 |
|---|---|
| `ci/audit-scope.js` | `pnpm audit` 결과를 워크스페이스 경로로 걸러 게이트에 반영(web-ci, mobile-ci) |
| `load-test/k6-smoke.js` | 상용화 스모크 부하: 스크리너 조회, 로그인 잠금, 주문 rate limit (`BASE_URL=http://localhost:8080 k6 run …`) |

## 다른 곳에 있는 스크립트

목적이 분명한 하네스는 그 디렉터리에 둔다.

- `bench/` — 부하·카오스·실험 하네스(`bench/run.sh`, `bench/chaos/`, `bench/experiments/`). [bench/README.md](../bench/README.md)
- `infra/db/` — 백업·PITR·복구 리허설. 운영 CronJob(`infra/k8s/base/db-backup.yaml`)이 같은 스크립트를 쓴다. `make db-backup`
- `apps/web/scripts/` — 화면 캡처 등 웹 전용
