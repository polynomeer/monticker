#!/usr/bin/env bash
# 두 벤치마크 결과를 비교합니다.
# 사용법: ./bench/compare.sh bench/results/load_A.json bench/results/load_B.json

A="${1:-bench/results/latest_load.json}"
B="${2:-bench/results/latest_smoke.json}"

python3 << PYEOF
import json, sys

def load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception as e:
        print(f"파일 로드 실패: {path} — {e}")
        sys.exit(1)

a, b = load("$A"), load("$B")

METRICS = [
    ("http_req_duration",        "전체 응답시간"),
    ("screener_duration",        "스크리너"),
    ("stocks_duration",          "종목 검색"),
    ("events_duration",          "이벤트 타임라인"),
    ("http_req_failed",          "에러율"),
]

print(f"\n{'':=<70}")
print(f"  벤치마크 비교")
print(f"  A: $A")
print(f"  B: $B")
print(f"{'':=<70}")
print(f"  {'지표':<25} {'A (avg/p95)':<20} {'B (avg/p95)':<20} {'변화'}")
print(f"  {'-'*65}")

for key, label in METRICS:
    ma = a.get("metrics", {}).get(key, {}).get("values", {})
    mb = b.get("metrics", {}).get(key, {}).get("values", {})
    if not ma or not mb:
        continue

    if key == "http_req_failed":
        va = ma.get("rate", 0) * 100
        vb = mb.get("rate", 0) * 100
        diff = vb - va
        arrow = "↑" if diff > 0 else "↓" if diff < 0 else "→"
        color = "\033[31m" if diff > 0.5 else "\033[32m" if diff < -0.5 else "\033[33m"
        print(f"  {label:<25} {va:.2f}%{'':>14} {vb:.2f}%{'':>14} {color}{arrow}{abs(diff):.2f}%\033[0m")
    else:
        avg_a = ma.get("avg", 0)
        p95_a = ma.get("p(95)", 0)
        avg_b = mb.get("avg", 0)
        p95_b = mb.get("p(95)", 0)
        diff_pct = ((avg_b - avg_a) / avg_a * 100) if avg_a else 0
        arrow = "↑" if diff_pct > 5 else "↓" if diff_pct < -5 else "→"
        color = "\033[31m" if diff_pct > 10 else "\033[32m" if diff_pct < -10 else "\033[33m"
        print(f"  {label:<25} {avg_a:.1f}ms / {p95_a:.1f}ms{'':<4} {avg_b:.1f}ms / {p95_b:.1f}ms{'':<4} {color}{arrow}{abs(diff_pct):.1f}%\033[0m")

# RPS 비교
rps_a = a.get("metrics", {}).get("http_reqs", {}).get("values", {}).get("rate", 0)
rps_b = b.get("metrics", {}).get("http_reqs", {}).get("values", {}).get("rate", 0)
diff_rps = rps_b - rps_a
color = "\033[32m" if diff_rps > 0 else "\033[31m"
print(f"  {'처리량(req/s)':<25} {rps_a:.1f}{'':>16} {rps_b:.1f}{'':>16} {color}{'↑' if diff_rps>0 else '↓'}{abs(diff_rps):.1f}\033[0m")
print(f"{'':=<70}\n")
PYEOF
