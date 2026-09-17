#!/usr/bin/env python3
"""M-002-tail 클래스 B — slow tick 순간을 브로커/DB 자원(docker stats)·브로커 GC 정지와 정렬한다.
   사용: m2-tail-broker.py <tail-rN.json> <tail-rN.dockerstats> <kafka-gc.log(docker logs)>"""
import sys, json, re, collections, datetime

j = json.load(open(sys.argv[1])); slow = j.get("slow_ticks", [])
o = j["e2e"].get("other", {})
print(f"# {sys.argv[1].split('/')[-1]}: slow_ticks={len(slow)}  p95={o.get('p95_ms')} p99={o.get('p99_ms')} max={o.get('max_ms')}")
if not slow: print("  (clean)"); sys.exit(0)
spike_secs = sorted(collections.Counter(t["at_ms"]//1000 for t in slow).items(), key=lambda x:-x[1])[:5]
spike_set = set(s for s,_ in spike_secs)
print("  spike 초:", [(datetime.datetime.fromtimestamp(s).strftime('%H:%M:%S'), c) for s,c in spike_secs])

# docker stats: "<epoch_ms> <name> <cpu%> <memUsage...>"
rows = collections.defaultdict(list)  # name -> [(sec, cpu)]
for line in open(sys.argv[2], errors="ignore"):
    p = line.split()
    if len(p) >= 3 and p[2].endswith("%"):
        try: rows[p[1]].append((int(p[0])//1000, float(p[2].rstrip("%"))))
        except: pass
print("  --- 브로커/DB CPU% (스파이크 초 vs 비-스파이크 중앙값/최대) ---")
for name in ("monticker-kafka","monticker-postgres","monticker-redis"):
    data = rows.get(name, [])
    if not data: continue
    at_spike = [c for s,c in data if s in spike_set]
    off = sorted(c for s,c in data if s not in spike_set)
    med = off[len(off)//2] if off else 0
    print(f"    {name}: 스파이크 초 CPU={sorted(at_spike, reverse=True)[:5]}  | 평소 median={med:.0f}% max={max((c for _,c in data),default=0):.0f}%")

# 브로커 GC (docker logs, -Xlog:gc 시각 포함): "[2026-..T..][info][gc] GC(N) Pause ... X.Yms"
if len(sys.argv) > 3:
    pauses = []
    for line in open(sys.argv[3], errors="ignore"):
        mt = re.search(r'\[(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+[+-]\d{4})\]', line)
        mp = re.search(r'Pause .*? (\d+\.\d+)ms', line)
        if mt and mp:
            ts = mt.group(1)[:-2] + ":" + mt.group(1)[-2:]   # +0000 → +00:00
            pauses.append((datetime.datetime.fromisoformat(ts).timestamp(), float(mp.group(1))))
    big = [p for p in pauses if p[1] >= 50]
    print(f"  --- 브로커 GC: {len(pauses)}건, 50ms+ {len(big)}건, 최대 {max((p[1] for p in pauses),default=0):.0f}ms ---")
    if big:
        aligned = sum(1 for s in spike_set if any(abs(s-int(pt)) <= 2 for pt,_ in big))
        print(f"    스파이크 초 중 브로커 GC(±2s)와 겹침: {aligned}/{len(spike_set)}")
        for pt,pm in sorted(big,key=lambda x:-x[1])[:5]:
            print(f"      {datetime.datetime.fromtimestamp(pt).strftime('%H:%M:%S')}  {pm:.0f}ms")
