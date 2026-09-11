#!/usr/bin/env bash
# CH-04 Elasticsearch 정지 (resilience-plan §6.2)
#
# 가설: 검색은 DB 폴백으로 전환되어 5xx가 0이고, search_fallback_total{index}가 증가한다(P1-2).
#   사용자는 결과 품질 저하만 겪는다. ES 복구 후 폴백 카운터가 더 이상 증가하지 않는다.
set -u
source "$(dirname "$0")/lib.sh"
fb() { curl -s "$API/actuator/prometheus" | grep '^search_fallback_total' | sed -E 's/.*index="([^"]+)".*\} /    \1=/'; }
searches() {
  probe "GET /api/stocks/search"  "$API/api/stocks/search?query=%EC%82%BC%EC%84%B1"
  probe "GET /api/news/search"    "$API/api/news/search?query=%EC%82%BC%EC%84%B1"
  probe "GET /api/events/search"  "$API/api/events/search?query=%EC%82%BC%EC%84%B1"
}
echo "== 1. 정상"; searches; echo "  fallback:"; fb
echo "== 2. 주입: docker compose stop elasticsearch"; compose stop elasticsearch >/dev/null; sleep 2
echo "== 3. 관측 (ES 없음) — 5xx가 나오면 실패"; searches; echo "  fallback:"; fb
echo "== 4. 복구: docker compose start elasticsearch"; compose start elasticsearch >/dev/null
for i in $(seq 1 60); do curl -sf http://localhost:${ELASTICSEARCH_PORT:-9200}/_cluster/health >/dev/null 2>&1 && break; sleep 2; done
echo "== 5. 복구 후"; before=$(fb | md5); searches; after=$(fb | md5); echo "  fallback:"; fb
[ "$before" = "$after" ] && echo "  폴백 카운터 정지 — ES 경로 복구 확인" || echo "  폴백 카운터가 아직 증가 — ES 재연결 지연"
