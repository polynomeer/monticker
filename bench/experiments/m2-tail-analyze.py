#!/usr/bin/env python3
"""M-002 §4.4 꼬리 스파이크 분석 — slow_ticks 를 (1) 시간 클러스터, (2) 분 경계 정렬, (3) 파티션 분포,
   (4) GC 정지 시각과의 정렬로 나눠 원인을 가른다. 사용: m2-tail-analyze.py <tail-r*.json> <tail-r*.gc.log>"""
import sys, json, re, collections

j = json.load(open(sys.argv[1]))
slow = j.get("slow_ticks", [])
thr = j.get("slow_tick_threshold_ms")
print(f"# slow_ticks: {len(slow)} (threshold {thr}ms), total ticks {j['ticks']}, e2e other p95={j['e2e'].get('other',{}).get('p95_ms')} p99={j['e2e'].get('other',{}).get('p99_ms')} max={j['e2e'].get('other',{}).get('max_ms')}")
if not slow:
    print("no slow ticks"); sys.exit(0)

# (2) 분 경계 정렬: ms_into_minute 를 초 버킷으로
sec = collections.Counter(int(t["ms_into_minute"]//1000) for t in slow)
print("\n## 분 경계 정렬 (ms_into_minute → 초 버킷, 상위 8):")
for s,c in sorted(sec.items(), key=lambda x:-x[1])[:8]:
    print(f"  {s:>2}s: {c}  ({100*c/len(slow):.0f}%)")
near0 = sum(c for s,c in sec.items() if s <= 1)
print(f"  → 0~1초(분 경계 직후) 비율: {100*near0/len(slow):.0f}%")

# (1) 시간 클러스터: at_ms 를 1초 버킷으로, 스파이크가 몇 개의 순간에 몰렸나
tsec = collections.Counter(t["at_ms"]//1000 for t in slow)
clusters = sorted(tsec.items())
print(f"\n## 시간 클러스터: slow tick 이 나타난 서로 다른 '초'의 수 = {len(tsec)} (총 {len(slow)}개)")
top = sorted(tsec.items(), key=lambda x:-x[1])[:5]
import datetime
for ts,c in top:
    print(f"  {datetime.datetime.fromtimestamp(ts).strftime('%H:%M:%S')}: {c}개 몰림")

# (3) 파티션 분포
part = collections.Counter(t["partition"] for t in slow)
print(f"\n## 파티션 분포: {dict(sorted(part.items()))}")
stock = collections.Counter(t["stock"] for t in slow)
print(f"## 상위 종목: {stock.most_common(5)}")

# (4) GC 정렬
if len(sys.argv) > 2:
    pauses = []  # (epoch_ms, pause_ms)
    for line in open(sys.argv[2]):
        # [2026-... ][info][gc] GC(12) Pause ... 123.456ms  — time 데코레이터로 ISO 시각
        mt = re.search(r'\[(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+)', line)
        mp = re.search(r'(\d+\.\d+)ms', line)
        if mt and mp and "Pause" in line:
            dt = datetime.datetime.fromisoformat(mt.group(1))
            pauses.append((dt.timestamp()*1000, float(mp.group(1))))
    big = [p for p in pauses if p[1] >= 50]
    print(f"\n## GC: pause {len(pauses)}건, 50ms+ {len(big)}건, 최대 {max((p[1] for p in pauses), default=0):.0f}ms")
    # 각 slow tick 이 직전 GC 큰 정지(±200ms 창)와 겹치나
    aligned = 0
    for t in slow:
        for pt, pm in big:
            if abs(t["at_ms"] - pt) <= max(pm, 200):
                aligned += 1; break
    print(f"  slow tick 중 50ms+ GC 정지와 겹치는 비율: {100*aligned/len(slow):.0f}%")
    if big:
        print("  큰 GC 정지 상위:", sorted((round(p[1]) for p in big), reverse=True)[:5])
