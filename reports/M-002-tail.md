# M-002 후속 — 실시간 파이프라인 p95 꼬리 스파이크 규명

> **페이퍼 트레이딩 규모 개인 프로젝트, 로컬 한 대 측정 — 실제 운영 트래픽 검증이 아니다.** [M-002 §4.4](M-002.md)가
> "증명하지 못했다"고 남긴 꼬리 스파이크를, 느린 틱의 벽시계 시각을 기록해 원인별로 정렬해 다시 판다.

- 스크립트: `bench/experiments/m2-tail.sh`, 분석 `m2-tail-analyze.py`·`m2-tail-analyze2.py`
- 원본: [`M-002/raw/tail/`](M-002/raw/tail/)(수정 전, 인라인 flush), [`tail-after/`](M-002/raw/tail-after/)·[`tail-safepoint/`](M-002/raw/tail-safepoint/)(수정 후)
- 실험일: 2026-09-17

## 1. 방법

최적 조건 P6·C6, 202종목·~2,015 tick/s 를 180초 단일 실행하며, e2e(생성→컨슈머 수신) ≥ 500ms 인 틱마다
**벽시계 수신 시각·분내 위치(ms_into_minute)·종목·파티션**을 `TickOrderMonitor`(experiment 프로파일)에 남긴다.
worker 는 `-Xlog:gc*,safepoint` 로 GC·safepoint 정지를, `[candle-drain]` 로그로 캔들 배치 flush 순간을 남긴다.
느린 틱 시각을 (a) 캔들 flush, (b) safepoint, (c) GC 와 정렬해 원인을 가른다.

## 2. 결과 — 스파이크는 두 종류다

8회 실행(인라인 flush 2회 + 배치 flush 6회), 각 180초. 스파이크가 난 4회는 모두 **한 순간(1~4초)에 모든 파티션이 동시에** 튀었다.

| 실행 | 빌드 | slow ticks | other p99 | max | 스파이크 위치 | GC 정렬 | safepoint 정렬 | flush 정렬 |
|---|---|---|---|---|---|---|---|---|
| tail-r1 | 인라인(콜드) | 2,729 | 423 | 1,013 | **분 경계 +0~1s (80%)** | 0% | — | 인라인 |
| tail-r2 | 인라인(웜) | 0 | 51 | 279 | — | — | — | — |
| after-r1 | 배치(콜드) | 0 | 42 | 227 | — | — | — | — |
| after-r2 | 배치(웜) | 1,896 | 342 | 1,527 | :19~22·:49~50 (경계 아님) | 0% | — | 0% |
| sp-r1 | 배치 | 0 | 91 | 264 | — | — | — | — |
| sp-r2 | 배치 | 0 | — | — | — | — | — | — |
| sp-r3 | 배치 | 5,000+ | 852 | 1,318 | 10:47:13~16 (경계 아님) | 0% | **0%** (최대 137ms, 스파이크 밖) | 0% |
| sp-r4 | 배치 | 5,000+ | 1,331 | 2,332 | 10:52:33~36 (경계 아님) | 0% | **0%** (50ms+ safepoint 0건) | 0% |

### 2.1 클래스 A — 분 경계 캔들 flush 스톨 (수정함)

인라인 flush 빌드의 콜드 실행(tail-r1)에서 느린 틱의 **80%가 분 경계 직후 0~1초**에 몰렸다. 원인: `CandleAggregator.onTick`이
분이 넘어갈 때 종목마다 **리스너 스레드에서 동기로** candles_1m·candles_1d upsert 2건을 했다. 202종목이 거의 동시에 넘어가면
그 스레드가 수십 ms 동안 틱을 못 읽어, 뒤에 쌓인 틱의 e2e 가 500ms~1s 로 튄다. GC 아님(0% 정렬). 콜드에서 두드러진 것은
Timescale 당일 chunk 최초 생성·캐시 콜드가 겹쳐서다.

**수정**: flush 를 리스너 스레드에서 뗐다 — 완결 캔들을 큐에 넣고 `@Scheduled(1s)`가 배치로 upsert(808건 개별 → 4배치, 배치당 최대 202캔들 90~115ms, 스케줄러 스레드). 수정 후 6회에서 **분 경계 스파이크는 한 번도 나지 않았다**(경계 정렬 0%). flush 타이머로 개별 flush 는 p99 17ms(콜드 max 181ms)로 원래 싸다는 것도 확인.

### 2.2 클래스 B — 간헐적 JVM 전역 fetch 스톨 (미해결, 환경 아티팩트 유력)

배치 flush 빌드에서도 6회 중 3회(~40%)에서 스파이크가 났다. 이들은 **분 경계도, drain flush 도, GC 도, safepoint 도 아니다**:
- 모든 파티션이 **한 순간(1~4초)에 동시에** 튄다 → 특정 스레드/파티션/DB 경합이 아니라 JVM 전역 스톨.
- GC 정렬 0%, **50ms+ safepoint 정렬 0%**(sp-r4 는 50ms+ safepoint 가 아예 0건인데도 스파이크 발생), drain flush 정렬 0%.
- worker JVM 로그 어디에도 원인이 없다.

worker 안에서 원인을 찾을 수 없다는 것이 결론이다. 남는 후보는 **worker 밖** — Kafka 단일 브로커(컨테이너 2 CPU·힙 512m)나
공유 호스트의 CPU/IO 경합이다. 같은 머신에서 gateway(202 goroutine)+worker(리스너 6스레드)+Kafka 가 10코어를 나눠 쓰므로,
브로커나 호스트가 1~4초 fetch 를 멈추면 모든 컨슈머의 e2e 가 동시에 뛴다 — 관측된 신호와 정확히 일치한다.

**증명하지 못한 것**: 브로커/호스트 측이 원인이라는 직접 증거(브로커 GC·CPU 로그)는 이 실험에 없다. 확정하려면
브로커 측 GC·CPU 계측과 **격리된 prod 유사 부하 환경**([engineering-backlog §9](../docs/engineering-backlog.md))이 필요하다.
페이퍼 트레이딩 규모에서는 실사용 영향이 없고(다음 틱이 곧 온다), 운영(전용 브로커·RF≥2·충분한 리소스)에서는 이 특정 스톨이
재현되지 않을 가능성이 높다.

## 3. 결론

- 실시간 SLO(p99 300ms)를 흔들던 꼬리 스파이크는 **두 원인**이었다. 하나(A, 분 경계 flush)는 리스너 스레드에서 DB I/O 를
  뗌으로써 제거했다. 다른 하나(B)는 worker 밖(브로커/호스트) 스톨로 좁혔고, 격리 환경에서 확정할 일로 남긴다.
- **방법론 교훈**: M-002 §4.4 가 "증명 못 함"으로 남긴 것은 40초 창의 분 경계 교차 여부라는 거친 지표로 봤기 때문이다.
  느린 틱의 **벽시계 시각을 원인 로그(flush·GC·safepoint)와 정렬**하니 A 는 규명되고 B 는 worker 밖으로 좁혀졌다.

## 4. 재실행

```bash
# 인프라·빌드는 reports/M-002 §7. worker 는 이 커밋의 배치 flush 버전.
OUT=reports/M-002/raw/tail-safepoint P=6 C=6 DURATION=180 THRESH=500 RUNS=4 bench/experiments/m2-tail.sh
for r in 1 2 3 4; do python3 bench/experiments/m2-tail-analyze2.py reports/M-002/raw/tail-safepoint/tail-r$r.json \
  reports/M-002/raw/tail-safepoint/tail-r$r.gc.log reports/M-002/raw/tail-safepoint/worker-tail-r$r.log; done
# 클래스 A(인라인 flush) 재현: 이 커밋 이전 CandleAggregator + 콜드 DB(candles truncate)로 위와 동일 실행
```
