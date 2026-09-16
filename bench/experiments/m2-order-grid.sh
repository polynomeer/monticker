#!/usr/bin/env bash
# M-002(a) — market.ticks 파티션 수 P × 컨슈머 수 C × 키 방식에 따른 처리량·e2e 지연·종목별 순서 위반.
#
# 부하 모델: Go market-gateway, 202종목 × 100ms 간격 = 약 2,020 tick/s 고정(TICK_INTERVAL_MS=100). 종목별 seq 부여.
# 컨슈머 C = worker 프로세스 1개의 리스너 스레드 수(KAFKA_CONSUMER_CONCURRENCY). 프로세스 수가 아니다 — (b)에서 2프로세스.
# 조건마다: worker 재기동(C) + 토픽 재생성(P) → RUNS 회 반복: 모니터 리셋 → 게이트웨이 HOLD 초 → 정지 → 랙 소진 → 스냅샷.
# 결과: $OUT/m2a-P{p}-C{c}-{key}-r{n}.json (worker 스냅샷 + 프로세스 리소스), $OUT/m2a-summary.tsv
# 실행: bench/experiments/m2-order-grid.sh    (환경변수: P_LIST C_LIST KEYS RUNS HOLD, lib.sh 참고)
source "$(dirname "$0")/lib.sh"
P_LIST="${P_LIST:-1 3 6 12}"; C_LIST="${C_LIST:-1 3 6}"; KEYS="${KEYS:-stock none}"; RUNS="${RUNS:-3}"; HOLD="${HOLD:-45}"
INTERVAL="${INTERVAL:-100}"
env_snapshot
SUM="$OUT/m2a-summary.tsv"
[ -n "${APPEND:-}" ] && [ -f "$SUM" ] || printf 'P\tC\tkey\trun\tticks\ttick_s\tviolations\tdups\tgaps\tp50_ms\tp95_ms\tp99_ms\tmax_ms\tworker_cpu_cores\tworker_heap_mb\tworker_threads\tkafka_stats\n' > "$SUM"
WORKER_PID=""; GW_PID=""
trap 'stop_pid "$GW_PID"; stop_pid "$WORKER_PID"' EXIT

for key in $KEYS; do
for p in $P_LIST; do
for c in $C_LIST; do
  # 키 없음(라운드로빈)은 P=1 이면 키 방식과 같다 — 건너뛴다
  [ "$key" = none ] && [ "$p" = 1 ] && continue
  stop_pid "$WORKER_PID"; WORKER_PID=""
  recreate_ticks_topic "$p"
  start_worker "worker-P$p-C$c-$key" "$WORKER_PORT" KAFKA_CONSUMER_CONCURRENCY="$c" || exit 1
  sleep 5   # 컨슈머 그룹 가입·파티션 할당
  warmup "${WARMUP:-20}" TICK_INTERVAL_MS="$INTERVAL" TICK_SEQ=true TICK_KEY_MODE="$key"
  for r in $(seq 1 "$RUNS"); do
    tag="m2a-P$p-C$c-$key-r$r"
    curl -s -X POST "$WORKER/experiment/tick-order/reset" >/dev/null
    sample_proc "$WORKER" "$HOLD" "$OUT/$tag.proc" & SP=$!
    start_gateway TICK_INTERVAL_MS="$INTERVAL" TICK_SEQ=true TICK_KEY_MODE="$key"
    sleep "$HOLD"
    stop_pid "$GW_PID"; GW_PID=""
    wait "$SP" 2>/dev/null
    ks=$(docker_stats)
    wait_drain 60 || log "  경고: 60s 내 랙 미소진 (lag=$(group_lag))"
    curl -s "$WORKER/experiment/tick-order" > "$OUT/$tag.json"
    python3 - "$OUT/$tag.json" "$(proc_summary "$OUT/$tag.proc")" "$HOLD" "$p" "$c" "$key" "$r" "$ks" >> "$SUM" <<'PY'
import sys,json
s=json.load(open(sys.argv[1])); pr=json.loads(sys.argv[2]); hold=int(sys.argv[3])
e=s["e2e"].get("other") or {}
print("\t".join(str(x) for x in [sys.argv[4],sys.argv[5],sys.argv[6],sys.argv[7],s["ticks"],round(s["ticks"]/hold),s["violations"],s["dups"],s["gaps"],
  e.get("p50_ms"),e.get("p95_ms"),e.get("p99_ms"),e.get("max_ms"),pr["cpu_cores_avg"],pr["heap_mb_max"],pr["threads_max"],sys.argv[8]]))
PY
    log "  $tag: $(tail -1 "$SUM" | cut -f5-13 | tr '\t' ' ')"
  done
done; done; done
log "완료 → $SUM"
