# 검색 인덱스 — `SearchIndexMappingMismatch` · `SearchIndexDltGrowing` · `SearchFallbackSustained`

## 증상
- `SearchFallbackSustained`: 10분간 인덱스별 DB 폴백 20건 초과 — ES가 죽었거나 연결이 안 된다. **서비스는 산다**(검색이 DB LIKE로 느려질 뿐, CH-04 PASS).
- `SearchIndexMappingMismatch`: 기동 시 설계 매핑과 실제가 다르다 — 검색이 설계대로 동작하지 않는다(날짜 정렬 실패, nori 미적용).
- `SearchIndexDltGrowing`: `search.index` 배치가 4회 재시도 후 실패 — ES가 오래 죽었거나 문서가 매핑을 위반.

## 1차 확인 (3단계)
1. `curl $ES/_cluster/health` — red/yellow/연결 불가. K8s에 ES가 **아직 없다**(human-action-items) — 있어야 할 환경에서 없으면 그게 원인.
2. `GET /api/admin/search/indices` (ADMIN) — 인덱스별 `mismatches` 목록.
3. DLT 내용: `kafka-console-consumer --topic search.index-dlt --from-beginning --property print.headers=true` → `kafka_dlt-exception-message`.

## 완화
- **ES 다운**: 복구를 기다린다. 색인 이벤트는 Outbox·재시도가 보관한다(ADR-042) — 복구 후 자동 따라잡는다. 5분 넘게 죽어 DLT로 간 배치는 아래 재처리.
- **매핑 불일치**: `POST /api/admin/search/reindex/{index}` — 인덱스를 지우고 설계 매핑으로 다시 만들어 DB에서 채운다. 그 동안 그 인덱스 검색은 DB 폴백. `stocks`·`news_articles`(1만 건)는 수 초, `alert_histories`(5만 건)는 수십 초.
- **DLT 재처리**: 원인 해결 후 컨슈머 그룹 `monticker-search-indexer` 오프셋을 DLT 발생 시각 이전으로 되감는다(색인은 멱등) — `kafka-consumer-groups.sh --reset-offsets --to-datetime`. 7일 retention 안이어야 한다. 넘었으면 재색인.

## 근본 원인 조사
- 매핑 불일치는 대개 `@Document` 클래스 변경 후 재색인 누락 — 배포 노트에 재색인이 포함됐는지.
- DLT의 매핑 위반(`mapper_parsing_exception`)은 코드가 보낸 문서와 매핑의 불일치 — 페이로드 생성부(`SearchIndexEvent` 발행 지점).

## 에스컬레이션
- 급하지 않다. 하루 넘게 폴백이면 티켓을 인시던트로 올린다(검색 지연이 사용자 체감).
