# Secret Manager 전환 템플릿

`../secret.yaml`는 git에 커밋된 **평문 placeholder Secret**이다(대부분 빈 문자열이며, `DB_USER`/
`DB_PASSWORD`만 이 저장소 전체에서 이미 쓰는 개발용 기본값 `monticker`/`monticker`다 — 실제
프로덕션 값이 유출된 적은 없다). `secret.yaml` 상단 주석이 이미 "실제 배포 전 반드시 값을
교체하거나 External Secrets Operator 사용"이라고 경고하고 있었지만, 실제로 그 전환이
적용됐는지는 확인되지 않은 상태였다 — [docs/launch-plan.md](../../../../docs/launch-plan.md)
Phase 2에서 이 상태를 그대로 기록하고, 이 디렉터리에 실제 전환에 쓸 템플릿을 준비해 뒀다.

## 왜 `.example` 확장자인가

이 디렉터리의 파일은 `.yaml.example`이다 — `../kustomization.yaml`의 `resources:` 목록에
없으므로 `kubectl apply -k`로 실수로 적용될 일이 없다. 실제로 쓰려면:

1. 실제 시크릿 백엔드(AWS Secrets Manager / GCP Secret Manager / HashiCorp Vault 중 택1)를
   프로비저닝한다 — 이건 실제 클라우드 계정이 있어야 하는 작업이라 여기서 대신할 수 없다.
2. 클러스터에 [External Secrets Operator](https://external-secrets.io)를 설치한다.
3. 이 디렉터리의 두 파일에서 `.example`을 떼고, `<...>`로 표시된 부분을 실제 값으로 채운다.
4. `../kustomization.yaml`의 `resources:`에서 `secret.yaml`을 지우고 이 두 파일로 교체한다.
5. `kubectl apply -k .` — ExternalSecret이 주기적으로 실제 백엔드에서 값을 읽어와 기존과
   동일한 이름(`monticker-secrets`)의 Kubernetes Secret을 생성/갱신한다. `api.yaml`/`worker.yaml`
   등은 `envFrom.secretRef.name: monticker-secrets`로만 참조하므로 **이 파일들은 전혀 손댈
   필요가 없다** — Secret을 누가 만들었는지는 신경 쓰지 않는다.

## 예시가 AWS Secrets Manager인 이유

가장 흔한 선택지라 예시로 삼았을 뿐, 이 프로젝트가 AWS를 써야 한다는 권고가 아니다. GCP Secret
Manager나 Vault를 쓴다면 `secretstore.yaml.example`의 `provider:` 블록만 [External Secrets
Operator 문서](https://external-secrets.io/latest/provider/aws-secrets-manager/)의 해당
프로바이더 섹션으로 교체하면 되고, `externalsecret.yaml.example`의 키 매핑 구조는 거의 동일하게
유지된다.
