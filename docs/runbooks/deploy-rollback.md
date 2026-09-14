# 배포 롤백

## 언제
배포 후 30분 안에 `ApiErrorBudgetBurn`·`OrderPathDown`·`LedgerMismatch`·5xx 급증·readiness 실패가 오면, **원인 조사보다 롤백이 먼저다**. 롤백은 싸고 조사는 비싸다.

## 되돌리기 (K8s)
```bash
kubectl -n monticker rollout history deploy/api
kubectl -n monticker rollout undo deploy/api            # 직전 리비전
kubectl -n monticker rollout undo deploy/api --to-revision=<n>
kubectl -n monticker rollout status deploy/api
```
worker·web도 같은 방식. 이미지는 GHCR에 태그별로 남아 있다(`deploy-images.yml`).
overlay의 `images[].newTag`를 고정 태그로 바꿔 `kubectl apply -k`하는 것이 재현 가능한 롤백이다 — `latest`로 굴리지 않는다.

## 되돌리기 전에 확인할 것 — 마이그레이션
Flyway는 앞으로만 간다. 새 버전이 마이그레이션을 적용했다면 **이전 코드가 그 스키마에서 동작하는가**를 봐야 한다.
- 컬럼 추가·인덱스 추가·테이블 추가(V43·V44·V45 류): 이전 코드도 동작한다 — 그냥 롤백.
- 컬럼 삭제·NOT NULL 추가·타입 변경: 이전 코드가 깨질 수 있다. 예: V45 `alert_rules.stock_id NOT NULL` — 이전 API가 NULL을 넣으려 하면 실패(400이 아니라 500). 이 경우 롤백 대신 **핫픽스 전진**.
- 규칙: 파괴적 마이그레이션은 **두 배포로 나눈다**(코드가 새 컬럼을 안 쓰게 한 뒤 다음 배포에서 컬럼 제거).

## 되돌리면 안 되는 것
- **원장·잔고 데이터**. 코드는 되돌려도 데이터는 그대로다. 롤백 뒤 반드시 대사(`POST /api/admin/batch/ledger-reconciliation`).
- **Kafka 파티션 수**(ADR-040): 늘린 건 줄일 수 없다. 이전 코드가 파티션 수를 가정하지 않으므로 무방.
- **ES 인덱스 매핑**: 새 코드가 재색인했다면 이전 코드의 `SearchIndexManager`가 불일치를 알람할 수 있다 — 정보성.

## 롤백 후
1. readiness UP, 5xx 평탄, 대시보드 Service Health 정상.
2. 대사 실행, `event_publication` 미완료 확인 — 새 버전이 만든 이벤트를 이전 버전이 역직렬화 못 하면 여기 남는다(Modulith는 클래스 이름으로 역직렬화). 그 행들은 새 버전 재배포 때 처리된다.
3. 사고 노트: 무엇을 배포했고 무엇이 아팠는지. 원인 조사는 여기서부터.

## 에스컬레이션
- 롤백해도 증상이 남으면 배포가 원인이 아니다 — 해당 알람의 런북으로.
