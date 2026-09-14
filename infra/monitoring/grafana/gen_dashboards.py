#!/usr/bin/env python3
"""
resilience-plan §4.6 대시보드 5종 생성기. 편집은 여기서 하고 `python3 gen_dashboards.py`로 JSON을 다시 만든다.
JSON을 손으로 고치면 다음 생성 때 사라진다. 메트릭 이름은 실제 /actuator/prometheus 노출과 대조한다 — check-metrics.py.

패널 규칙:
- 분위수는 histogram_quantile(…_bucket) — pod 간 합산이 된다. summary(quantile 라벨)는 합산이 안 돼 쓰지 않는다.
- 카운터는 rate()·increase(), 게이지는 그대로. `_total`은 카운터에만 있다 (게이지는 Micrometer가 접미사를 뗀다).
- 알람과 같은 표현식을 쓴다 — 대시보드에서 본 값이 알람이 본 값이다.
- Kafka 컨슈머 메트릭의 topic 라벨은 '.'이 '_'로 치환돼 노출된다 (market.ticks → market_ticks).
"""
import json, pathlib

OUT = pathlib.Path(__file__).parent / "dashboards"
API = 'job="monticker-api"'
WK = 'job="monticker-worker"'

def ts(title, targets, unit=None, w=12, h=8, desc=None, thresholds=None, stack=False, legend="bottom", max_=None):
    p = {"type": "timeseries", "title": title,
         "targets": [{"expr": e, "legendFormat": l, "refId": chr(65 + i)} for i, (e, l) in enumerate(targets)],
         "gridPos": {"w": w, "h": h},
         "fieldConfig": {"defaults": {"custom": {"lineWidth": 1, "fillOpacity": 8, "stacking": {"mode": "normal" if stack else "none"}}}, "overrides": []},
         "options": {"legend": {"displayMode": "list", "placement": legend}, "tooltip": {"mode": "multi"}}}
    if unit: p["fieldConfig"]["defaults"]["unit"] = unit
    if max_ is not None: p["fieldConfig"]["defaults"]["max"] = max_
    if desc: p["description"] = desc
    if thresholds:
        p["fieldConfig"]["defaults"]["thresholds"] = {"mode": "absolute", "steps": [{"color": "green", "value": None}] + [{"color": c, "value": v} for v, c in thresholds]}
        p["fieldConfig"]["defaults"]["custom"]["thresholdsStyle"] = {"mode": "line"}
    return p

def stat(title, expr, unit=None, w=4, h=4, desc=None, thresholds=None, decimals=None, legend=""):
    p = {"type": "stat", "title": title, "targets": [{"expr": expr, "refId": "A", "legendFormat": legend, "instant": True}],
         "gridPos": {"w": w, "h": h},
         "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "background", "graphMode": "none", "textMode": "value"},
         "fieldConfig": {"defaults": {}, "overrides": []}}
    if unit: p["fieldConfig"]["defaults"]["unit"] = unit
    if decimals is not None: p["fieldConfig"]["defaults"]["decimals"] = decimals
    if desc: p["description"] = desc
    steps = [{"color": "green", "value": None}] + [{"color": c, "value": v} for v, c in (thresholds or [])]
    p["fieldConfig"]["defaults"]["thresholds"] = {"mode": "absolute", "steps": steps}
    return p

def row(title): return {"type": "row", "title": title, "collapsed": False, "gridPos": {"w": 24, "h": 1}}

def layout(panels):
    x = y = 0; rowh = 0; out = []
    for i, p in enumerate(panels):
        w, h = p["gridPos"]["w"], p["gridPos"]["h"]
        if p["type"] == "row" or x + w > 24:
            y += rowh; x = 0; rowh = 0
        p["gridPos"].update({"x": x, "y": y}); p["id"] = i + 1
        x += w; rowh = max(rowh, h)
        if p["type"] == "row": y += 1; x = 0; rowh = 0
        out.append(p)
    return out

def dashboard(uid, title, tags, panels, refresh="15s", rng="now-1h"):
    return {"uid": uid, "title": title, "tags": ["monticker"] + tags, "schemaVersion": 38, "refresh": refresh, "editable": True,
            "time": {"from": rng, "to": "now"}, "timezone": "browser", "panels": layout(panels), "templating": {"list": []}, "annotations": {"list": []}}

P95_HTTP = f'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{API}, uri!~"/actuator.*"}}[5m])))'
P99_HTTP = P95_HTTP.replace("0.95", "0.99")
# 5xx 시계열이 아직 없으면 sum()은 빈 벡터다 — "에러 0"을 0으로 보이게 or vector(0)
ERR_RATIO = f'(sum(rate(http_server_requests_seconds_count{{{API}, status=~"5.."}}[5m])) or vector(0)) / sum(rate(http_server_requests_seconds_count{{{API}}}[5m]))'

service_health = dashboard("monticker-service-health", "monticker — Service Health", ["oncall"], [
    row("SLO — resilience-plan §4.3 (30일 에러버짓)"),
    stat("가용성 (5xx 제외, 5m)", f'(1 - {ERR_RATIO}) * 100', unit="percent", decimals=3, thresholds=[(0, "red"), (99.9, "green")], desc="SLO 99.9%. 5xx 비율의 보수."),
    stat("조회 p95 (ms)", f'{P95_HTTP} * 1000', unit="ms", thresholds=[(200, "orange"), (500, "red")], desc="SLO p95 < 200ms — ApiLatencyHigh 알람과 같은 식"),
    stat("조회 p99 (ms)", f'{P99_HTTP} * 1000', unit="ms", thresholds=[(500, "orange"), (1000, "red")]),
    stat("주문 p99 (ms)", f'histogram_quantile(0.99, sum by (le) (rate(matching_saga_submit_seconds_bucket{{{API}}}[5m]))) * 1000', unit="ms", thresholds=[(500, "orange"), (1000, "red")], desc="거래 경로 SLO p99 < 500ms (사가 전체)"),
    stat("에러버짓 소진율 (1h burn)", f'{ERR_RATIO} / (1 - 0.999)', decimals=2, thresholds=[(2, "orange"), (14.4, "red")], desc="ApiErrorBudgetBurn 알람의 식: 5m 5xx 비율 ÷ 허용치(0.1%). 14.4 = 1시간에 30일 버짓의 2% 소진"),
    stat("활성 알람", 'count(ALERTS{alertstate="firing"}) or vector(0)', thresholds=[(1, "red")], desc="Prometheus가 firing 상태로 보는 규칙 수"),
    row("트래픽 · 에러"),
    ts("HTTP 처리율 (req/s, uri별 상위)", [(f'topk(12, sum by (uri) (rate(http_server_requests_seconds_count{{{API}, uri!~"/actuator.*"}}[1m])))', "{{uri}}")], unit="reqps"),
    ts("5xx / 4xx (req/s)", [(f'sum(rate(http_server_requests_seconds_count{{{API}, status=~"5.."}}[1m])) or vector(0)', "5xx"), (f'sum(rate(http_server_requests_seconds_count{{{API}, status=~"4.."}}[1m])) or vector(0)', "4xx")], unit="reqps", thresholds=[(1, "red")]),
    ts("지연 p50 / p95 / p99 (ms)", [(P95_HTTP.replace("0.95", "0.50") + " * 1000", "p50"), (P95_HTTP + " * 1000", "p95"), (P99_HTTP + " * 1000", "p99")], unit="ms", thresholds=[(200, "orange"), (500, "red")]),
    ts("느린 URI 상위 (p95, ms)", [(f'topk(8, histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{{{API}, uri!~"/actuator.*"}}[5m]))) * 1000)', "{{uri}}")], unit="ms"),
    row("프로세스 — 어느 pod가 아픈가"),
    ts("JVM heap 사용률 (%)", [(f'sum by (instance) (jvm_memory_used_bytes{{{API}, area="heap"}}) / sum by (instance) (jvm_memory_max_bytes{{{API}, area="heap"}}) * 100', "{{instance}}")], unit="percent", max_=100, thresholds=[(85, "red")], desc="HighJvmHeapUsage 알람 85%"),
    ts("Hikari 활성 / 대기 (api)", [(f'hikaricp_connections_active{{{API}}}', "active {{instance}}"), (f'hikaricp_connections_pending{{{API}}}', "pending {{instance}}"), (f'hikaricp_connections_max{{{API}}}', "max")], desc="HikariPoolNearExhaustion(활성/최대 > 80%), HikariPendingThreads(> 0 for 2m)"),
    ts("JVM 스레드 / 모듈 이벤트 실행기", [(f'jvm_threads_live_threads{{{API}}}', "jvm live {{instance}}"), (f'executor_active_threads{{{API}, name="moduleEventExecutor"}}', "module-event active {{instance}}"), (f'executor_queued_tasks{{{API}, name="moduleEventExecutor"}}', "module-event queued {{instance}}")], desc="CH-05: 브로커 장애 중 리스너 스레드가 늘면 여기서 보인다 (유계 풀 4/16/1000). 살아 있는 스레드 수가 계속 오르면 누수"),
    ts("Redis fail-open / fail-closed (건/min)", [(f'sum by (op, policy) (rate(redis_command_failed_total{{{API}}}[1m])) * 60', "{{policy}} {{op}}")], desc="RedisFailOpenSustained · IdempotencyStoreDown. 없으면 = 실패 0 (카운터는 첫 실패 때 생긴다)"),
])

trading = dashboard("monticker-trading", "monticker — Trading", ["trading"], [
    row("주문 — 사가 (모의투자) · 실거래"),
    ts("주문 TPS (사가)", [(f'sum(rate(matching_saga_submit_seconds_count{{{API}}}[1m]))', "orders/s")], unit="reqps"),
    ts("주문 지연 p50 / p99 (ms)", [(f'histogram_quantile(0.50, sum by (le) (rate(matching_saga_submit_seconds_bucket{{{API}}}[5m]))) * 1000', "p50"), (f'histogram_quantile(0.99, sum by (le) (rate(matching_saga_submit_seconds_bucket{{{API}}}[5m]))) * 1000', "p99")], unit="ms", thresholds=[(500, "orange")]),
    ts("주문 상태 전이 (건/min)", [(f'sum by (to) (rate(order_state_transition_total{{{API}}}[1m])) * 60', "→ {{to}}")], stack=True),
    ts("주문 경로 5xx (OrderPathDown)", [(f'sum(rate(http_server_requests_seconds_count{{{API}, uri=~"/api/(matching|paper|brokerage)/.*", status=~"5.."}}[1m])) or vector(0)', "5xx/s")], unit="reqps", thresholds=[(0.01, "red")], desc="OrderPathDown 알람: 주문 경로 5xx가 2분간 지속되면 page"),
    row("리스크 게이트 (ADR-025) — 거부율이 갑자기 오르면 룰이 아니라 데이터를 의심할 것 (DailyLossRule 사례)"),
    ts("리스크 판정 (건/min)", [(f'sum by (result) (rate(risk_check_total{{{API}}}[5m])) * 60', "{{result}}")], stack=True),
    ts("거부 룰별 (건/min)", [(f'sum by (rule) (rate(risk_check_total{{{API}, result="blocked"}}[5m])) * 60', "{{rule}}")], stack=True),
    stat("거부율 (5m)", f'sum(rate(risk_check_total{{{API}, result="blocked"}}[5m])) / sum(rate(risk_check_total{{{API}}}[5m])) * 100', unit="percent", decimals=1, thresholds=[(30, "orange"), (60, "red")], desc="정상 거래에서 30%를 넘으면 룰 입력(잔고·일봉)이 깨진 것"),
    row("정합성 — 잔고 대사(ADR-043) · Saga · Outbox"),
    stat("대사 불일치 (24h, alert)", f'sum(increase(ledger_reconciliation_mismatch_total{{{API}, mode="alert"}}[24h])) or vector(0)', thresholds=[(1, "red")], desc="LedgerMismatch page. 자동 교정 금지 — runbook ledger-mismatch"),
    stat("대사 불일치 (24h, report)", f'sum(increase(ledger_reconciliation_mismatch_total{{{API}, mode="report"}}[24h])) or vector(0)', thresholds=[(1, "orange")], desc="LEDGER_RECON_MODE=report 기간의 발견 — 0이 되면 alert로 전환"),
    stat("대사 실행 (26h)", f'sum(increase(ledger_reconciliation_checked_total{{{API}}}[26h])) or vector(0)', thresholds=[(0, "red"), (1, "green")], desc="LedgerReconciliationDidNotRun: 평일 26시간 동안 0이면 배치가 안 돈 것"),
    stat("Saga 미완료", f'max(saga_incomplete{{{API}}})', thresholds=[(1, "orange")], desc="SagaIncomplete. recoverIncomplete가 5분마다 정리한다 — 10분 넘게 남으면 수동 검토"),
    stat("Outbox 미완료", f'max(outbox_pending{{{API}}})', thresholds=[(100, "orange")], desc="OutboxBacklog: > 100 또는 가장 오래된 것 > 10분"),
    stat("Outbox 최고령 (s)", f'max(outbox_oldest_age_seconds{{{API}}})', unit="s", thresholds=[(600, "orange")]),
    row("브로커 (KIS · Toss) — 실거래"),
    ts("서킷브레이커 상태 (1=CLOSED 2=OPEN 3=HALF_OPEN)", [(f'max by (name) (resilience4j_circuitbreaker_state{{{API}, name=~"kis|toss"}} * on() group_left() 1)', "{{name}}")], desc="BrokerCircuitOpen page — runbook broker-cb-open. 잔고 조회는 OPEN 중 503(0원이 아니다)"),
    ts("브로커 느린 호출 / 실패 비율 (%)", [(f'max by (name) (resilience4j_circuitbreaker_slow_call_rate{{{API}, name=~"kis|toss"}})', "slow {{name}}"), (f'max by (name) (resilience4j_circuitbreaker_failure_rate{{{API}, name=~"kis|toss"}})', "failure {{name}}")], unit="percent", max_=100, thresholds=[(50, "red")], desc="P0-2: slow-call(5초) 50% 또는 실패율 50%면 OPEN. -1은 창이 아직 안 찬 것"),
    ts("브로커 호출 결과 (건/min)", [(f'sum by (name, kind) (rate(resilience4j_circuitbreaker_calls_seconds_count{{{API}, name=~"kis|toss"}}[1m])) * 60', "{{name}} {{kind}}"), (f'sum by (name) (rate(resilience4j_circuitbreaker_not_permitted_calls_total{{{API}, name=~"kis|toss"}}[1m])) * 60', "{{name}} not permitted (OPEN)")], stack=True),
])

realtime = dashboard("monticker-realtime-pipeline", "monticker — Realtime Pipeline", ["realtime"], [
    row("유입 — Kafka market.ticks"),
    ts("틱 처리량 (ticks/s, worker)", [(f'sum(rate(tick_latency_total_pipeline_seconds_count{{{WK}}}[1m]))', "processed/s")], unit="ops", desc="L-03: 단일 파티션 600/s → 동시성 4에서 5,000/s (ADR-044/046)"),
    ts("컨슈머 랙 (market.ticks, 파티션 최대)", [(f'max by (job) (kafka_consumer_fetch_manager_records_lag_max{{topic="market_ticks"}})', "{{job}}")], thresholds=[(60000, "red")], desc="TickPipelineStalled: 장중 3분간 처리 0 또는 랙 > 60,000 — runbook tick-stalled"),
    ts("단계별 지연 p99 (ms)", [(f'histogram_quantile(0.99, sum by (le) (rate(tick_latency_redis_write_seconds_bucket{{{WK}}}[1m]))) * 1000', "redis write"), (f'histogram_quantile(0.99, sum by (le) (rate(tick_latency_broadcast_seconds_bucket{{{WK}}}[1m]))) * 1000', "broadcast"), (f'histogram_quantile(0.99, sum by (le) (rate(tick_latency_total_pipeline_seconds_bucket{{{WK}}}[1m]))) * 1000', "total pipeline")], unit="ms", thresholds=[(300, "orange")], desc="§4.3 실시간 SLO p99 < 300ms"),
    ts("느린 틱 (> 임계, 건/min)", [(f'sum(rate(tick_pipeline_slow_total{{{WK}}}[1m])) * 60', "slow/min")], desc="ADR-046: 틱마다 WARN 찍던 것을 카운터로. 백로그를 소화 중이면 정상적으로 튄다"),
    row("fan-out — api STOMP (ADR-038/039)"),
    ts("WS 동접 (pod별)", [(f'ws_active_connections{{{API}}}', "{{instance}}")], desc="WsConnectionsSkewed: pod 간 편차 — 세션 어피니티/LB 확인"),
    ts("브로드캐스트 in → out (msg/s)", [(f'sum(rate(ws_broadcast_ticks_in_total{{{API}}}[1m]))', "ticks in"), (f'sum(rate(ws_broadcast_messages_out_total{{{API}}}[1m]))', "messages out"), (f'sum(rate(ws_market_summary_relayed_total{{{API}}}[1m]))', "summary relayed")], unit="ops", desc="conflation(100ms) 효과 = in 대비 out. L-02: 500연결에서 50,000 → p99 159ms"),
    stat("conflation 비율 (out/in)", f'sum(rate(ws_broadcast_messages_out_total{{{API}}}[5m])) / sum(rate(ws_broadcast_ticks_in_total{{{API}}}[5m]))', decimals=2, desc="1보다 작을수록 합쳐진다. 구독자가 많으면 1을 넘는 게 정상(구독자 수 × 종목)"),
    ts("브로드캐스트 실패 (건/min)", [(f'sum(rate(tick_broadcast_failed_total{{{API}}}[1m])) * 60', "failed/min")], thresholds=[(1, "orange")]),
    row("이벤트 · 알림 · 캔들"),
    ts("이벤트 탐지 (건/min)", [(f'sum by (source, type) (rate(stock_events_written_total{{{WK}}}[5m])) * 60', "{{source}} {{type}}")], stack=True),
    ts("알림 룰 인덱스", [(f'max(alert_rule_index_size{{{WK}}})', "rules"), (f'max(alert_rule_index_ready{{{WK}}})', "ready (1)")], desc="ADR-044: 0이면 DB 폴백 경로. 5분 보정 + Redis pub/sub"),
    ts("알림 평가 실패 / 발송 발행 실패 (건/min)", [(f'sum by (ruleType) (rate(alert_rule_eval_failed_total{{{WK}}}[5m])) * 60', "eval {{ruleType}}"), (f'sum(rate(alert_notify_publish_failed_total{{{WK}}}[5m])) * 60', "notify publish")], desc="AlertRuleEvalFailing — E7(VOLUME_SURGE가 무효 SQL로 조용히 죽어 있던) 재발 감지"),
    ts("캔들 flush 실패 (건/min)", [(f'sum(rate(candle_flush_failed_total{{{WK}}}[5m])) * 60', "failed/min")], thresholds=[(1, "red")], desc="CandleFlushFailing — 실패는 유실이다"),
    row("DLT"),
    ts("DLT 유입 (건/min, 토픽별)", [('sum by (topic) (rate(dlt_messages_total[5m])) * 60', "{{topic}}")], thresholds=[(1, "red")], desc="DltMessagesGrowing · SearchIndexDltGrowing. 0이어야 한다"),
])

data_stores = dashboard("monticker-data-stores", "monticker — Data Stores", ["infra"], [
    row("PostgreSQL / TimescaleDB — 커넥션 (scale-out-plan §3.8: 총량 vs max_connections)"),
    ts("Hikari 활성 (서비스별 합)", [('sum by (job) (hikaricp_connections_active)', "{{job}}")], stack=True, desc="pod당 20. api 6 pod + worker 3 = 최대 ~180"),
    ts("Hikari 대기 스레드", [('sum by (job, instance) (hikaricp_connections_pending)', "{{job}} {{instance}}")], thresholds=[(1, "orange")], desc="HikariPendingThreads: 대기가 생기면 풀이 말랐다 — 느린 쿼리(statement_timeout 30s) 먼저"),
    ts("커넥션 획득 p99 (ms)", [('max by (job) (hikaricp_connections_acquire_seconds_max) * 1000', "{{job}} (max)")], unit="ms", thresholds=[(1000, "orange")]),
    ts("커넥션 타임아웃 (건/min)", [('sum by (job) (rate(hikaricp_connections_timeout_total[5m])) * 60', "{{job}}")], thresholds=[(1, "red")], desc="3초 connection-timeout 초과 = 대량 500 직전"),
    row("Redis — Lettuce (P0-1: 200ms 타임아웃, fail-open/closed)"),
    ts("Redis 명령 지연 max (ms)", [('max by (job, command) (lettuce_command_completion_seconds_max) * 1000', "{{job}} {{command}}")], unit="ms", thresholds=[(200, "red")], desc="200ms를 넘으면 RedisGuard가 fail-open(레이트리밋·캐시)/fail-closed(멱등성 503)로 처리한다"),
    ts("Redis 명령 처리율 (cmd/s)", [('sum by (job) (rate(lettuce_command_completion_seconds_count[1m]))', "{{job}}")], unit="ops"),
    ts("Redis 실패 정책 발동 (건/min)", [('sum by (job, policy) (rate(redis_command_failed_total[5m])) * 60', "{{job}} {{policy}}")], desc="RedisFailOpenSustained(10분 20건) · IdempotencyStoreDown(2분 1건 page)"),
    row("Elasticsearch — ADR-042 아웃박스 색인"),
    ts("색인 처리 (docs/min)", [(f'sum by (op) (rate(search_index_documents_total{{{API}}}[5m])) * 60', "{{op}}")], stack=True),
    ts("색인 지연 p95 / p99 (s)", [(f'histogram_quantile(0.95, sum by (le) (rate(search_index_lag_seconds_bucket{{{API}}}[5m])))', "p95"), (f'histogram_quantile(0.99, sum by (le) (rate(search_index_lag_seconds_bucket{{{API}}}[5m])))', "p99")], unit="s", desc="이벤트 외부화 → ES 색인 완료. Outbox 재전송을 탄 이벤트는 5분+가 정상"),
    ts("검색 DB 폴백 (건/min, 인덱스별)", [(f'sum by (index) (rate(search_fallback_total{{{API}}}[5m])) * 60', "{{index}}")], thresholds=[(2, "orange")], desc="SearchFallbackSustained. CH-04: 컨테이너 배포에서 ES에 한 번도 연결된 적 없던 걸 이게 잡았다"),
    ts("매핑 불일치 (인덱스별, 1=불일치)", [(f'max by (index) (search_index_mapping_mismatch{{{API}}})', "{{index}}")], max_=1, thresholds=[(1, "red")], desc="SearchIndexMappingMismatch — 재색인 POST /api/admin/search/reindex/{index}"),
    row("Kafka — 컨슈머 그룹"),
    ts("컨슈머 랙 (토픽·그룹별 최대)", [('max by (topic, job) (kafka_consumer_fetch_manager_records_lag_max)', "{{topic}} @ {{job}}")]),
])

capacity = dashboard("monticker-capacity", "monticker — Capacity (주간 리뷰)", ["capacity"], [
    row("성장 추이 — scale-out-plan §2 T1/T2 기준선과 비교 (7일 창)"),
    ts("틱 처리량 (ticks/s, 1h 평균)", [(f'sum(rate(tick_latency_total_pipeline_seconds_count{{{WK}}}[1h]))', "ticks/s")], unit="ops", desc="T1 목표 12,000 종목 × 1/s. L-03 실측 상한: 동시성 4에서 5,000/s"),
    ts("HTTP 처리율 (req/s, 1h 평균)", [(f'sum(rate(http_server_requests_seconds_count{{{API}, uri!~"/actuator.*"}}[1h]))', "req/s")], unit="reqps"),
    ts("WS 동접 (합)", [(f'sum(ws_active_connections{{{API}}})', "connections")], desc="L-02: pod당 500 연결에서 p99 159ms. ADR-045 §4.3 SLO는 5,000 conn"),
    ts("주문 TPS (1h 평균)", [(f'sum(rate(matching_saga_submit_seconds_count{{{API}}}[1h]))', "orders/s")], unit="reqps"),
    row("처리 여유 — 랙 · 풀 · 큐"),
    ts("파티션당 처리량 (ticks/s ÷ 파티션 수)", [(f'sum(rate(tick_latency_total_pipeline_seconds_count{{{WK}}}[1h])) / count(count by (partition) (kafka_consumer_fetch_manager_records_lag_max{{topic="market_ticks"}}))', "per partition")], unit="ops", desc="ADR-040 산정 근거 ~2,000/s per partition — 실측이 이 값에 가까워지면 파티션 증설(장 마감 후)"),
    ts("Hikari 사용률 (%, 서비스별)", [('sum by (job) (hikaricp_connections_active) / sum by (job) (hikaricp_connections_max) * 100', "{{job}}")], unit="percent", max_=100, thresholds=[(80, "orange")]),
    ts("백테스트 큐 잔여 / 스레드", [(f'min(executor_queue_remaining_tasks{{{API}, name="backtestExecutor"}})', "queue remaining (20)"), (f'max(executor_active_threads{{{API}, name="backtestExecutor"}})', "active threads (max 4)")], desc="BacktestQueueSaturated. L-06: bulkhead 안에서 조회 p95 무영향"),
    ts("JVM heap 최대 대비 사용 (%)", [('max by (job) (jvm_memory_used_bytes{area="heap"}) / max by (job) (jvm_memory_max_bytes{area="heap"}) * 100', "{{job}}")], unit="percent", max_=100),
    row("적체 — 시간이 지나도 안 줄면 구조 문제"),
    ts("Outbox 미완료 · 최고령", [(f'max(outbox_pending{{{API}}})', "pending"), (f'max(outbox_oldest_age_seconds{{{API}}}) / 60', "oldest (min)")]),
    ts("DLT 누적 (7일)", [('sum by (topic) (increase(dlt_messages_total[7d]))', "{{topic}}")], stack=True),
    ts("검색 폴백 누적 (7일)", [(f'sum by (index) (increase(search_fallback_total{{{API}}}[7d]))', "{{index}}")], stack=True),
], refresh="5m", rng="now-7d")

for name, d in [("service-health", service_health), ("trading", trading), ("realtime-pipeline", realtime), ("data-stores", data_stores), ("capacity", capacity)]:
    (OUT / f"{name}.json").write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n")
    print(name, len(d["panels"]), "panels")
