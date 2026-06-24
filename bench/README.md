# monticker API Benchmark

## 빠른 시작

```bash
# API 서버가 실행 중이어야 합니다
./dev.sh   # 또는 백엔드만: cd backend/api && ./gradlew bootRun

# 기본 smoke 테스트 (1분)
./bench/run.sh

# 부하 테스트 (3분, 최대 50 VUs)
./bench/run.sh load

# 스트레스 테스트 (5분, 최대 100 VUs)
./bench/run.sh stress

# 스파이크 테스트 (갑작스러운 부하)
./bench/run.sh spike

# 실시간 대시보드 포함
./bench/run.sh load --dashboard
# → http://127.0.0.1:5665 에서 실시간 확인
```

## 결과 확인

```bash
# HTML 리포트 열기 (자동으로 생성됨)
open bench/reports/smoke_*.html
open bench/reports/load_*.html

# 두 결과 비교
./bench/compare.sh bench/results/latest_load.json bench/results/latest_smoke.json
```

## 시나리오별 설정

| 시나리오 | 최대 VUs | 소요 시간 | 목적 |
|---------|---------|---------|------|
| `smoke`  | 3       | 1분      | 기본 동작 확인 |
| `load`   | 50      | 3분      | 일반 운영 부하 |
| `stress` | 100     | 5분      | 한계 성능 탐색 |
| `spike`  | 100     | 1.5분    | 순간 급증 대응 |

## 임계값 (Thresholds)

| 지표 | smoke | load | stress |
|------|-------|------|--------|
| 에러율 | <1% | <1% | <5% |
| 전체 p95 | - | <300ms | <1000ms |
| 스크리너 p95 | <500ms | <200ms | - |
| 종목검색 p95 | <300ms | <150ms | - |

## 저장 위치

```
bench/
  results/        ← JSON 원본 데이터
    latest_smoke.json   (최신 smoke 결과)
    latest_load.json    (최신 load 결과)
    smoke_2026-*.json   (히스토리)
  reports/        ← HTML 리포트
    smoke_2026-*.html
    load_2026-*.html
```

## 환경 변수

```bash
BASE_URL=http://staging:8080 ./bench/run.sh load  # 다른 서버 대상
```
