# ADR-042 — 검색 설계(docs/elasticsearch.md)는 nori 형태소 분석기를 전제한다. 공식 이미지에는 nori가 없어서
# 명시적 매핑으로 인덱스를 만들면 "Unknown tokenizer type [nori_tokenizer]"로 실패한다 — 그래서 지금까지
# 모든 인덱스가 첫 save()의 동적 매핑으로 생성됐다(CH-04). 이 이미지가 compose·K8s 공통 ES 이미지다.
FROM elasticsearch:8.13.4
RUN bin/elasticsearch-plugin install --batch analysis-nori
