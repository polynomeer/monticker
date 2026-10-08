# User Manual

monticker를 사용하는 **최종 사용자**를 위한 문서입니다. 주식·퀀트 용어가 낯설다면 [docs/stock-knowledge/](../stock-knowledge/README.md)를 함께 보세요. 개발자용 구현 문서는 [docs/technical/](../technical/README.md), 제품 설계 배경은 [docs/domain/](../domain/README.md)을 참고하세요.

| 문서 | 내용 |
|------|------|
| [user-guide.md](./user-guide.md) | 전체 화면별 상세 사용법(2026-10 트레이딩 터미널 화면 기준) — 시작하기(동의·온보딩 시작 자금), 화면 구성(아이콘 레일·계좌 칩·패널), 홈, 스크리너, 종목 상세(차트 유형·그리기 도구·호가 깊이), 관심종목, 알림(읽음·시그널·전달 채널), 모의투자 주문(지정가·조건부·리스크 미리보기·영수증·감정 태그·진입 경로), 체결 엔진(접수 순 처리·대기 순번), 자동 주문 규칙, 리스크(섹터 한도·쿨링오프·근접 경고), 지갑·리플레이, 포트폴리오, 정산(KRX 영업일), 백테스팅, Quant Lab, 전략 마켓, Analytics, 실전투자(연동·해지·'결과 확인 중'·조건부 주문·리밸런싱), 종목 비교, 설정(접근성·방해 금지 시간), 구독, FAQ |

매뉴얼은 코드에서 동작을 확인한 내용만 적습니다. 화면에 "준비 중" 표식이 붙은 요소는 매뉴얼에서도 "준비 중"으로 적고, 동작하는 것처럼 설명하지 않습니다. 구현 계획은 [design-rollout-plan.md](../design-rollout-plan.md)에서 추적합니다.

---

## 스크린샷 다시 찍을 목록

`docs/images/`의 화면 캡처(마지막 갱신 2026-09-23)는 터미널 디자인으로 바뀌기 전 화면이라, 매뉴얼에서 참조를 모두 뺐습니다. 파일 자체는 루트 README 등 다른 곳에서 아직 쓰일 수 있어 지우지 않았습니다. 새로 찍은 뒤 해당 절에 이미지를 다시 넣으세요. 캡처 방법은 [CONTRIBUTING.md — 문서 스크린샷 다시 찍기](../../CONTRIBUTING.md#문서-스크린샷-다시-찍기)를 따릅니다.

캡처 스크립트(`apps/web/scripts/capture-screenshots.mjs`)에 이미 있는 화면 — 다시 찍기만 하면 됩니다.

- [ ] `home.png` — `/` 마켓 개요(지수 카드·이벤트 피드·퀀트 시그널·섹터 히트맵) → 매뉴얼 3장. 스크립트 주석·캡션의 "스크리너" 설명도 홈으로 고칠 것
- [ ] `stock-detail.png` — `/stocks/{코드}` 트레이딩 화면(주문·호가·차트·하단 포지션) → 5.2
- [ ] `watchlist.png` — `/watchlist` → 6장
- [ ] `alerts.png` — `/alerts`(이력·규칙·전달 채널) → 7장
- [ ] `matching.png` — `/matching`(오더북 대기열 "내 주문 · 대기 N번째"가 보이게) → 9장
- [ ] `watch-rules.png`, `watch-rules-history.png` — `/watch-rules` 내 규칙 / 발동 기록 → 10장
- [ ] `risk.png` — `/risk`(섹터 한도, "적용 대기 중인 변경"이 보이게) → 11장
- [ ] `wallet.png`, `wallet-timeline.png`, `wallet-score.png` — `/wallet` 돈의 이동 지도 / 원장 타임라인 / 점수 세부 지표 → 12장. 새 화면은 탭이 아니라 패널이므로 스크립트의 `clickText("원장 타임라인")`·`clickText("투자 점수")` 단계를 고칠 것
- [ ] `portfolio.png` — `/portfolio`(평균단가 오버레이의 매수·매도 마커가 보이게) → 13장
- [ ] `settlement.png` — `/settlement`(정산 캘린더·다가오는 휴장일) → 14장
- [ ] `backtest.png` — `/backtest`(캔들 + 매매 마커) → 15장
- [ ] `quant-lab.png`, `quant-lab-builder.png`, `quant-lab-backtest.png` — `/quant-lab`, `/quant-lab/builder`, `/quant-lab/{id}` → 16장
- [ ] `analytics.png`, `analytics-regime.png` — `/analytics` 위험-수익 분포(무작위 표본·샤프 최대 지점) / 시장 국면 → 18장. 스크립트의 `clickText("최적 비중 계산")`·`clickText("시장 국면")`을 새 버튼("분석 실행")·패널 구성에 맞출 것
- [ ] `brokerage.png`, `brokerage-orders.png`, `brokerage-conditional.png`, `brokerage-rebalance.png` — `/brokerage` 대시보드 / 주문(검증·확인 단계) / 조건부 주문 / 리밸런싱(예상 거래비용) → 19장. Mock 브로커 연동 계좌로 찍고 주황 "실전 계좌" 칩이 보이게
- [ ] `subscription.png` — `/subscription` → 22장

스크립트에 새로 추가해야 하는 화면:

- [ ] `screener.png` — `/screener` 필터 패널과 결과 → 4장
- [ ] `stock-drawing-tools.png` — 종목 차트의 그리기 도구 툴바와 그린 예시 → 5.4
- [ ] `stock-order-receipt.png` — 지정가 체결 후 투자 영수증 4단계(예약금 잠금 포함)와 감정 태그 → 8.5
- [ ] `wallet-replay.png` — `/wallet/replay` 캔들 리플레이·계획 준수율 → 12.4
- [ ] `quant-lab-market.png` — `/quant-lab/market` → 17장
- [ ] `brokerage-connect.png` — `/brokerage/connect` 연동 단계와 동의 3개(키 값은 비우거나 가린 상태) → 19.1
- [ ] `consent.png` — `/consent` 약관 재동의 → 1.2
- [ ] `onboarding-capital.png` — `/onboarding` 2단계(시작 자금 선택) → 1.4
- [ ] `settings-notifications.png`, `settings-appearance.png` — 방해 금지 시간·전달 채널 / 시세 색상·접근성 → 21장
