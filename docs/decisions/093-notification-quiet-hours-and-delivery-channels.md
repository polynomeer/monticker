# ADR-093: 방해 금지 시간은 푸시를 미루지 않고 보내지 않는다, 전달 채널은 같은 정책으로 계산한다

## Status
Accepted

[ADR-082](082-notification-preferences-enforced-at-delivery.md)(알림 설정을 발송 직전 한 정책 함수로 적용)의 Consequences
"방해 금지 시간이 생기면 정책 함수에 시간 조건을 더하고, 끌 수 없는 종류는 그대로 통과시킨다"를 이행한다. ADR-082의 결정은 그대로다.

## Context

`/settings/notifications`의 방해 금지 시간은 비활성 토글이었고, `/alerts`의 "전달 채널"은 `—`였다.

정해야 할 것이 셋이었다.

1. **방해 금지 시간에 들어온 알림을 어떻게 할까.**
   - (a) **미루기**: 구간이 끝날 때 몰아서 보낸다. Kafka에는 지연 전달이 없다. 그래서 지연 큐 테이블, 다중 인스턴스 클레임, 끝나는 시각의
     스케줄러가 필요하다. 게다가 가격 알림·거래량 급증은 시점이 생명이다. 아침 7시에 밤사이 "목표가 도달" 푸시 열 개가 한꺼번에 오면
     가격은 이미 움직였고, 지금 일어난 일처럼 읽힌다.
   - (b) **보내지 않기(푸시만)**: 그 시간의 푸시를 보내지 않는다. 기록은 남긴다. — 채택.
   - (c) 푸시 대신 이메일로 돌리기: 사용자가 고르지 않은 채널로 보내게 된다(ADR-082는 "고른 채널로만"이다).
2. **"같은 정책 코드"를 api에서 어떻게 쓸까.** 전달 채널 화면은 worker가 실제로 하는 일을 보여 줘야 한다. 그런데 api와 worker는
   빌드·Docker 컨텍스트·이미지가 따로이고 공유 모듈이 없다(ADR-065 Consequences). 공유 소스 디렉터리를 두 빌드에 넣으려면 두 Dockerfile의
   컨텍스트를 바꿔야 한다. 이것은 배포 경로 변경이라 이번 범위에서 위험이 크다.
3. **worker가 api보다 먼저 배포된다.** 마이그레이션은 api가 한다(worker는 `flyway.enabled=false`). 새 컬럼을 읽는 worker가 V90 이전
   스키마에서 깨지면 안 된다.

## Decision

1. **저장(V90)**: `notification_preferences`에 `quiet_hours_enabled`(기본 false)·`quiet_hours_start`(22:00)·`quiet_hours_end`(07:00)를
   더한다. 값은 **KST 벽시계 시각**이다. 한국은 서머타임이 없어 하루가 늘 24시간이라 시각만으로 충분하다. 시작 > 끝이면 자정을 넘는다.
   CHECK: 시작 ≠ 끝(0분인지 하루 종일인지 모호하다), 분 단위만 허용. api는 같은 규칙을 400으로 먼저 검사한다(`HH:mm`).
   저장 요청에 방해 금지 필드가 **없으면 지금 값을 유지한다**. 이 필드를 모르는 예전 화면이 저장해도 설정이 지워지지 않는다.
2. **정책**(worker `NotificationPolicy.plan(pref, category, marketingAgreed, at)`):
   - 판정은 `at`을 `Asia/Seoul`로 바꾼 벽시계 시각으로 한다(JVM 시간대와 무관). 구간은 시작을 포함하고 끝은 제외한다.
   - **끌 수 없는 종류**(`ORDER_OUTCOME`·`CONDITIONAL_ORDER`·`RISK_WARNING`)는 방해 금지 시간을 보지 않고 즉시 보낸다(푸시, 닿지 않으면 이메일).
   - 끌 수 있는 종류가 구간 안이고 원래 푸시할 것이었다면 `push=false`, `emailIfPushMissed=false`, `quietHoursHeld=true`로 둔다.
     **사용자가 고른 이메일은 그대로 보낸다.** 이메일은 소리가 나지 않는다.
   - 미루지 않는다. 구간이 끝나도 다시 보내지 않는다.
3. **기록**: 알림 규칙 발동(`AlertDispatcher`)은 `alert_histories`에 그대로 남는다. 푸시만 막혀 아무것도 보내지 않았으면
   `delivery_status = 'QUIET_HOURS'`다(설정으로 끈 것은 `SUPPRESSED`). 알림 화면에서 아침에 볼 수 있다. `notify.user` 경로는 이력 테이블이
   없어 로그로만 남는다(ADR-065와 같다).
4. **같은 규칙, 한 곳의 데이터**: 규칙을 사례표 `backend/contracts/notification-delivery-policy.json`에 둔다. 표에는 종류별
   alwaysOn·inApp과 사례(설정·KST 시각·동의 → 기대 계획)가 들어 있다. worker `NotificationPolicy`(실제 발송)와 api
   `NotificationDeliveryPolicy`(화면용 거울)가 **둘 다 이 표로 테스트된다**(`NotificationPolicyContractTest`·
   `NotificationDeliveryPolicyContractTest`, Gradle `monticker.contractsDir`). 한쪽만 바꾸면 다른 쪽 테스트가 실패한다. api
   `NotificationCategory`에는 worker 전용 `PRICE_ALERT`·`VOLUME_SURGE`를 더했다. api는 이 종류로 발행하지 않는다.
5. **전달 채널 API**: `GET /api/users/me/notification-preferences/channels`는 방해 금지 상태(`activeNow` 포함), 마케팅 동의
   (`ConsentService.isAgreed` — 발행 전 확인과 같은 기준), 종류별 `push`·`email`·`emailFallback`·`inApp`·`pushDuringQuietHours`를 돌려준다.
   화면은 규칙을 다시 계산하지 않고 그대로 보여 준다.
6. **배포 순서**: worker 먼저. `NotificationPreferenceReader`는 결과 집합에 `quiet_hours_enabled` 열이 없으면 방해 금지 꺼짐으로 읽는다.
   `notify.user` 메시지 모양은 바뀌지 않았다. 그 소비자는 이미 모르는 필드를 무시한다(ADR-082).
7. **카카오 알림톡**은 외부 비즈메시지 연동이 필요해 계속 비활성이다(`kakaoAvailable=false`).

## Reasons

- 미루기는 큐·스케줄러·클레임을 새로 만든다. 그런데 미뤄서 얻는 것이 시점이 지난 가격 알림이다. 이력이 남으니 정보는 잃지 않는다.
- 끌 수 없는 종류를 정책 첫 줄에서 통과시킨다. 그래서 방해 금지 설정 때문에 이중 주문 경고나 보호 상실 알림을 못 받는 경우가 구조적으로 없다.
- 공유 빌드 모듈 없이 "한 곳의 규칙"을 지킨다. 표가 계약이고, 두 구현은 그 계약의 테스트를 받는다. 표의 설정 필드 이름이 api·worker 설정 필드와
  같은지도 테스트가 확인한다.

## Consequences

- 규칙 구현이 두 벌이다(worker 실제, api 거울). 표가 어긋남을 잡아 주지만, 표에 없는 경우는 잡지 못한다. 규칙을 바꿀 때는 표에 사례부터 더한다.
- 방해 금지 시간에 푸시가 막힌 알림은 다시 오지 않는다. 기기만 쓰고 이메일을 고르지 않은 사용자는 아침에 알림 화면에서 이력으로만 본다.
  `notify.user` 종류(체결·퀀트 신호 등)는 이력 화면이 없다. 각 화면(주문·포워드 테스트)에서 확인한다.
- 기기가 없는 웹 사용자는 방해 금지 시간에 "푸시 대체 이메일"도 받지 않는다. 대체 이메일은 닿지 않은 푸시를 위한 것이고, 일부러 보내지 않은
  푸시를 위한 것이 아니기 때문이다.
- 방해 금지 시간은 KST 고정이다. 해외 거주 사용자의 현지 시간대는 지원하지 않는다.
- 퀀트 시그널의 알림 이력 적재(ADR-090)와 함께 표와 api `NotificationCategory`의 `QUANT_SIGNAL.inApp`을 true로 바꿨다.

## Revisit When

- 사용자 시간대 설정이 생길 때. 그때는 `quiet_hours_*`에 시간대를 더하거나 사용자 시간대로 판정한다.
- "아침 요약"(구간 동안 막힌 알림을 한 통으로) 요구가 생길 때. 미루기 대신 요약 발송을 검토한다.
- api와 worker가 공유 모듈(또는 단일 빌드)을 갖게 될 때. 정책을 한 벌로 합치고 표는 그 테스트로 남긴다.
- 카카오 알림톡 연동을 시작할 때. 채널 하나를 더하고 방해 금지 시간을 적용할지 정한다(알림톡은 야간 발송 제한 규정이 따로 있다).
