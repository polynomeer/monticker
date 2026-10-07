# ADR-042 — 검색 설계(docs/elasticsearch.md)는 nori 형태소 분석기를 전제한다. 공식 이미지에는 nori가 없어서
# 명시적 매핑으로 인덱스를 만들면 "Unknown tokenizer type [nori_tokenizer]"로 실패한다 — 그래서 지금까지
# 모든 인덱스가 첫 save()의 동적 매핑으로 생성됐다(CH-04). 이 이미지가 compose·K8s 공통 ES 이미지다.
# 버전은 api·worker의 elasticsearch-java 클라이언트(Spring Boot BOM, 지금 8.18.1)와 맞춘다. 8.13.4였을 때 클라이언트가
# cluster.health 응답의 `unassigned_primary_shards`(8.16+)를 필수로 읽다 실패해 /actuator/health가 항상 DOWN이었다.
FROM elasticsearch:8.18.1
RUN bin/elasticsearch-plugin install --batch analysis-nori
