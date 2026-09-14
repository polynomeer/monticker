#!/usr/bin/env python3
"""
알람 규칙·대시보드의 PromQL을 실제 Prometheus에 던져 (1) 파싱 오류 (2) 알려지지 않은 메트릭 이름을 잡는다.
"존재하지 않는 메트릭에 건 알람은 영원히 조용하다" — OutboxBacklog·SagaIncomplete가 실제로 그랬다(게이지 `_total`).
사용: python3 infra/monitoring/check-metrics.py http://localhost:9090
      (api·worker가 스크레이프되고 있어야 한다. 데이터가 없는 표현식은 '빈 결과'로만 표시한다 — 카운터는 첫 이벤트 때 생긴다.)
"""
import json, re, sys, urllib.request, urllib.parse, glob, pathlib

PROM = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:9090"
ROOT = pathlib.Path(__file__).parent

def q(expr):
    u = f"{PROM}/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    try:
        with urllib.request.urlopen(u, timeout=10) as r: return json.load(r)
    except urllib.error.HTTPError as e: return json.load(e)

names = set(json.load(urllib.request.urlopen(f"{PROM}/api/v1/label/__name__/values"))["data"])
KEYWORDS = {"sum","rate","increase","histogram_quantile","max","min","avg","by","on","count","abs","day_of_week","and","or","unless","without","le","time","clamp_min","ceil","floor","changes","absent","group_left","group_right","vector","scalar","round","topk","bottomk","bool","ignoring","offset","label_replace","hour","minute","month","year","day_of_month"}
def metric_names(expr):
    e = re.sub(r'"[^"]*"', '""', expr)   # 라벨 값·문자열 제거
    e = re.sub(r'\{[^}]*\}', '', e)      # 라벨 셀렉터 제거
    e = re.sub(r'\b(by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)', '', e)   # 라벨 이름 목록 제거
    return {m for m in re.findall(r'\b([a-zA-Z_:][a-zA-Z0-9_:]*)\b', e) if m not in KEYWORDS and not m[0].isdigit() and m.upper() != m}

def exprs_from_rules():
    txt = (ROOT / "alert-rules.yml").read_text()
    for name, body in re.findall(r'- alert: (\S+)(.*?)(?=\n      - alert:|\Z)', txt, re.S):
        m = re.search(r'expr:\s*(\|-?)?\s*\n?((?:.*\n)*?)(?=\s+(?:for|labels):)', body)
        expr = re.sub(r'#.*', '', m.group(2)) if m else ''
        yield f"alert {name}", " ".join(expr.split())

def exprs_from_dashboards():
    for f in sorted(glob.glob(str(ROOT / "grafana/dashboards/*.json"))):
        d = json.load(open(f))
        for p in d["panels"]:
            for t in p.get("targets", []):
                yield f"{pathlib.Path(f).stem} / {p['title']}", t["expr"]

bad = 0
for where, expr in list(exprs_from_rules()) + list(exprs_from_dashboards()):
    r = q(expr)
    if r.get("status") != "success":
        print(f"PARSE  {where}: {r.get('error')}"); bad += 1; continue
    unknown = [m for m in metric_names(expr) if m not in names and m != "ALERTS"]
    empty = not r["data"]["result"]
    if unknown:
        print(f"UNKNOWN {where}: {unknown}"); bad += 1
    elif empty:
        print(f"empty   {where}")
print(f"\n{'FAIL' if bad else 'OK'} — {bad} problem(s)")
sys.exit(1 if bad else 0)
