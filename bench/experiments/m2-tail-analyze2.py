#!/usr/bin/env python3
"""M-002 §4.4 심화 — slow tick 이 (a) 캔들 drain flush 순간, (b) 비-GC safepoint 정지와 정렬되는지 본다.
   사용: m2-tail-analyze2.py <tail-rN.json> <tail-rN.gc.log> <worker-*.log>"""
import sys, json, re, collections, datetime

j = json.load(open(sys.argv[1])); slow = j.get("slow_ticks", [])
o = j["e2e"].get("other", {})
print(f"# {sys.argv[1].split('/')[-1]}: slow_ticks={len(slow)}  other p95={o.get('p95_ms')} p99={o.get('p99_ms')} max={o.get('max_ms')}")
if not slow: print("  (clean)"); sys.exit(0)

spike_secs = sorted(collections.Counter(t["at_ms"]//1000 for t in slow).items(), key=lambda x:-x[1])
print("  spike 순간(초 단위, 상위):", [(datetime.datetime.fromtimestamp(s).strftime('%H:%M:%S'), c) for s,c in spike_secs[:6]])
spike_ms = [t["at_ms"] for t in slow]

# (a) drain flush 시각 (worker 로그의 [candle-drain] ... at <epoch_ms>)
drains = []
if len(sys.argv) > 3:
    for line in open(sys.argv[3], errors="ignore"):
        m = re.search(r'\[candle-drain\] flushed (\d+) candles in (\d+)ms at (\d+)', line)
        if m: drains.append((int(m.group(3)), int(m.group(1)), int(m.group(2))))  # t0, n, durMs
    big = [d for d in drains if d[1] >= 50]
    aligned = sum(1 for s in spike_ms if any(d[0]-300 <= s <= d[0]+d[2]+1500 for d in big))
    print(f"  drain flush: {len(drains)}건(≥50캔들 {len(big)}건, 최대 {max((d[1] for d in drains),default=0)}캔들 / {max((d[2] for d in drains),default=0)}ms)")
    print(f"  → slow tick 중 큰 drain(-0.3s~+1.5s) 창과 겹치는 비율: {100*aligned/len(slow):.0f}%")

# (b) safepoint 정지: `Safepoint "NAME", ... Total: N ns`
pauses = []  # (epoch_ms, ms, name)
for line in open(sys.argv[2], errors="ignore"):
    mt = re.search(r'\[(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+)', line)
    mn = re.search(r'Safepoint "([^"]+)".*Total: (\d+) ns', line)
    if mt and mn:
        pauses.append((datetime.datetime.fromisoformat(mt.group(1)).timestamp()*1000, int(mn.group(2))/1e6, mn.group(1)))
big_sp = [p for p in pauses if p[1] >= 50]
byname = collections.Counter(p[2] for p in big_sp)
print(f"  safepoint: {len(pauses)}건, 50ms+ {len(big_sp)}건, 최대 {max((p[1] for p in pauses),default=0):.0f}ms  종류(50ms+): {dict(byname)}")
if big_sp and slow:
    aligned = sum(1 for s in spike_ms if any(abs(s-pt) <= max(pm,300) for pt,pm,_ in big_sp))
    print(f"  → slow tick 중 50ms+ safepoint 와 겹치는 비율: {100*aligned/len(slow):.0f}%")
    for pt,pm,nm in sorted(big_sp,key=lambda x:-x[1])[:5]:
        print(f"    {datetime.datetime.fromtimestamp(pt/1000).strftime('%H:%M:%S.%f')[:-3]}  {pm:.0f}ms  {nm}")
