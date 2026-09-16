#!/usr/bin/env python3
"""실험 summary.tsv → 보고서용 마크다운 표. 조건(그룹 컬럼)별로 반복 실행을 한 행에 모은다.
   값 표기: 숫자 컬럼은 "중앙값 (최소–최대)" — 반복 3회의 분포를 감추지 않는다. 평균은 쓰지 않는다.
   사용: summarize.py <tsv> <그룹컬럼,…> <표시컬럼,…> [--per-run 컬럼,…]   (--per-run 은 "r1 / r2 / r3" 로 나열)"""
import sys, csv, statistics as st
tsv, groups, cols = sys.argv[1], sys.argv[2].split(","), sys.argv[3].split(",")
per_run = sys.argv[5].split(",") if len(sys.argv) > 5 and sys.argv[4] == "--per-run" else []
rows = list(csv.DictReader(open(tsv), delimiter="\t"))
def num(v):
    try: return float(v)
    except: return None
def fmt(v):
    if v is None: return "—"
    return f"{v:.0f}" if abs(v) >= 100 or v == int(v) else f"{v:.1f}"
order, buckets = [], {}
for r in rows:
    k = tuple(r[g] for g in groups)
    if k not in buckets: buckets[k] = []; order.append(k)
    buckets[k].append(r)
print("| " + " | ".join(groups + ["runs"] + cols) + " |")
print("|" + "---|" * (len(groups) + 1 + len(cols)))
for k in order:
    b = buckets[k]; out = list(k) + [str(len(b))]
    for c in cols:
        vals = [num(r.get(c, "")) for r in b]
        if c in per_run: out.append(" / ".join(fmt(v) for v in vals)); continue
        vs = [v for v in vals if v is not None]
        if not vs: out.append("—"); continue
        med = st.median(vs); lo, hi = min(vs), max(vs)
        out.append(fmt(med) if lo == hi else f"{fmt(med)} ({fmt(lo)}–{fmt(hi)})")
    print("| " + " | ".join(out) + " |")
