#!/usr/bin/env python3
"""M-002(b) — Redis 스트림(experiment:tick-order, XRANGE --raw 덤프)을 읽어 리밸런스 전후의 순서·중복·유실을 센다.

입력: 덤프 파일, w2 SIGKILL 시각(ms), w2 재기동 시각(ms). 출력: JSON 한 덩어리.
  expected     = 종목별 max(seq) 합 — 게이트웨이는 seq 를 빠뜨리지 않으므로 이게 발행 수의 하한이다
  unique       = 스트림에 한 번 이상 나타난 (stock, seq) 수 → lost = expected − unique
  dups         = 같은 (stock, seq) 가 두 번 이상
  violations   = 스트림 도착 순서(= 처리 순서의 근사)에서 seq 가 그 종목의 지금까지 max 보다 작은 경우(중복 제외)
  handover_gap = w2 가 맡았던 파티션마다 "w2 마지막 처리 → w1 첫 처리" 간격의 최대(ms). 리밸런스 동안 그 파티션이 멈춘 시간
  w2_rejoin_s  = 재기동 명령 → w2 가 다시 틱을 처리한 첫 시각
"""
import sys, json, collections

path, tkill, trestart = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
lines = [l.rstrip("\n") for l in open(path)]
# --raw XRANGE 출력: id 한 줄, 그 다음 필드/값이 번갈아 한 줄씩(6쌍=12줄)
recs = []
i = 0
while i < len(lines):
    if "-" in lines[i] and lines[i].replace("-", "").isdigit():
        f = {}
        j = i + 1
        while j + 1 < len(lines) and lines[j] in ("s", "q", "p", "w", "g", "r"):
            f[lines[j]] = lines[j + 1]; j += 2
        if len(f) == 6:
            recs.append((int(f["s"]), int(f["q"]), int(f["p"]), f["w"], int(f["g"]), int(f["r"])))
        i = j
    else:
        i += 1

maxseq = collections.defaultdict(int); seen = set(); dups = 0; viol = 0; affected = set()
last_w2 = {}; first_w1_after = {}; w2_parts = set(); w2_first_after_restart = None
e2e_w1_during = []
for s, q, p, w, g, r in recs:
    if q < 0: continue
    key = (s, q)
    if key in seen: dups += 1
    else:
        if q < maxseq[s]: viol += 1; affected.add(s)
        seen.add(key); maxseq[s] = max(maxseq[s], q)
    if w == "w2":
        if r <= tkill: w2_parts.add(p); last_w2[p] = max(last_w2.get(p, 0), r)
        elif r >= trestart and w2_first_after_restart is None: w2_first_after_restart = r
    elif w == "w1" and r > tkill and p in w2_parts and p not in first_w1_after:
        first_w1_after[p] = r
    if w == "w1" and tkill <= r <= trestart: e2e_w1_during.append(r - g)
gaps = [first_w1_after[p] - last_w2[p] for p in w2_parts if p in first_w1_after]
e2e_w1_during.sort()
p99 = e2e_w1_during[int((len(e2e_w1_during) - 1) * 0.99)] if e2e_w1_during else None
expected = sum(maxseq.values())
print(json.dumps({
    "run": None, "ticks_logged": len(recs), "unique": len(seen), "expected": expected, "lost": expected - len(seen),
    "dups": dups, "violations": viol, "stocks_affected": len(affected),
    "w2_partitions": sorted(w2_parts), "handover_gap_ms": max(gaps) if gaps else None, "handover_gaps_ms": sorted(gaps),
    "w2_rejoin_s": round((w2_first_after_restart - trestart) / 1000, 1) if w2_first_after_restart else None,
    "w1_e2e_p99_ms_during": p99,
}, ensure_ascii=False))
