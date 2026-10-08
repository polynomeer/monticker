# ADR-100: 관심종목 뉴스·공시 알림은 worker가 수집 직후 팬아웃하고, 시간당 상한으로 양을 묶는다

## Status
Accepted

[ADR-082](082-notification-preferences-enforced-at-delivery.md)(발송 직전 정책 적용), [ADR-090](090-quant-signal-alert-history-fanout.md)
(사용자 소유 알림 이력·dedup 키), [ADR-093](093-notification-quiet-hours-and-delivery-channels.md)(방해 금지 시간·공유 사례표),
[ADR-094](094-separate-outbox-tables-per-app.md)(앱별 아웃박스)를 따른다. 기존 결정을 번복하지 않는다.

## Context

`/settings/notifications`의 "뉴스·공시"는 V78부터 `news_alert_push`·`news_alert_email` 열이 있었지만 보내는 코드가 없었다.
PR #180에서 화면을 "준비 중"으로 막았다. 이것을 실제로 동작하게 한다: 관심종목에 든 종목의 새 뉴스나 DART 공시가 들어오면 그 사용자에게
(사용자, 기사·공시)당 한 번, 알림 이력 행 + 설정대로 푸시·이메일.

정해야 할 것:

1. **어디서 팬아웃하나.** 수집(`NewsCollector`·`DisclosureCollector`)은 worker에 있다. api로 보내려면 새 Kafka 토픽과 api 소비자가 필요하다.
   ADR-090의 시그널 팬아웃은 신호가 api에서 나기 때문에 api에 있었다.
   - (a) api: worker → 새 토픽 → api 소비자 → `alert_histories` + `UserNotificationCommand`(notify.user) → worker 발송. 홉이 둘이고
     토픽·재시도·DLT를 새로 만든다.
   - (b) worker, 발송은 notify.user를 거친다: 롤링 배포 중 NEWS를 모르는 **이전 worker 소비자**가 메시지를 받으면
     `NotificationCategory.fromWire`가 모르는 이름을 끌 수 없는 종류로 읽는다(ADR-082). 설정·방해 금지 시간을 무시하고 보낸다.
   - (c) worker, 발송도 같은 JVM의 내부 이벤트로 — 채택.
2. **양을 어떻게 묶나.** 뉴스 수집은 30분마다 종목당 최대 5건이다. 관심종목 20개면 시간당 수백 건이 될 수 있다.
   - 묶음 요약(digest): "최근 1시간 뉴스 12건" 한 통. 지연 큐·끝나는 시각의 스케줄러·요약 행 갱신이 필요하다. ADR-093이 미루기를 거절한
     이유와 같다.
   - 중요도 기준: 공시에는 `importance_score`가 있다. 뉴스에는 감성 점수만 있고 중요도가 없다.
   - 사용자별 시간당 상한 — 채택(공시는 중요도 기준도 함께).
3. **지난 기사.** 뉴스 API는 최근 결과를, DART는 최근 1일 목록을 돌려준다. 처음 배포하거나 수집이 밀렸다가 돌면 지난 기사가 한꺼번에 새 행이 된다.

## Decision

**흐름(worker `newsalert` 패키지)**

```
NewsCollector.persist / DisclosureCollector.insertEvent   (수집 트랜잭션)
  └ news_articles | stock_events INSERT
    + SearchIndexEvent(기존)
    + NewsAlertCandidateEvent(kind, sourceId, stockId, title, publishedAt, importance?)   // 내부 이벤트 → worker_outbox
                                         │ 커밋 후 (@ApplicationModuleListener: 비동기, 새 트랜잭션, 실패 시 재전송)
NewsAlertFanout.on ◄─────────────────────┘
  신선도·중요도 아니면 끝(기록 없음)
  pg_advisory_xact_lock(뉴스 팬아웃 전역 키)
  받는 사람 = 그 종목을 자기 관심종목에 넣은 사용자
             (탈퇴 제외, news_alert_push OR news_alert_email — 행이 없으면 기본값 켜짐)
             + 그 사람의 최근 1시간 NEWS 이력 수 / 그중 알림까지 나간 수
  사용자마다 상한 판정 →
    INSERT alert_histories(user_id, category='NEWS', dedup_key='news:{id}'|'disclosure:{id}', delivery_status='QUEUED'|'CAPPED')
      ON CONFLICT (user_id, dedup_key) DO NOTHING RETURNING id
    새 행만 → SearchIndexEvent(alert_histories, ruleType=NEWS)       (외부화 → api 색인)
            → NewsAlertNotifyEvent (QUEUED일 때만)                  (내부 이벤트)
                                         │ 커밋 후
NewsAlertDelivery.on ◄───────────────────┘
  UserNotificationDispatcher.dispatch(category=NEWS, dedupKey='{kind}:{id}:u{user}')
    → NotificationPolicy(NEWS): 전체 알림·채널·종류·방해 금지 시간 → Redis 중복 제거 → 푸시(닿지 않으면 이메일)
```

- **이중 쓰기 없음**: 이력 행·색인 이벤트·발송 이벤트는 한 트랜잭션이다. 푸시·메일은 커밋 뒤 별도 리스너가 보낸다.
  실패하면 각 발행 기록이 미완료로 남아 `OutboxResubmissionConfig`가 다시 보낸다.
- **멱등**: 행은 유니크 인덱스(ADR-090 V87)가 판정한다. 재전송되면 이미 받은 사용자는 건너뛰고 이벤트도 내지 않는다. 발송은 디스패처의
  `notify:user:sent:{dedupKey}`가 한 번 더 막는다.
- **소유**: 받는 사람은 그 사용자 **자신의** `watchlist_groups.user_id`로만 정한다. 보유 종목·다른 사람의 관심종목은 보지 않는다.

**종류 NEWS**

- 공유 사례표 `backend/contracts/notification-delivery-policy.json`에 `NEWS`(alwaysOn=false, inApp=true)와 사례 6개를 더했다.
  worker `NotificationCategory`·api `NotificationCategory`(전달 채널 표시용 거울)에 같은 이름을 더하고 둘 다 `news_alert_*`에 묶었다.
- 광고성이 아니다(사용자가 고른 종목의 사실 정보) — 마케팅 동의를 보지 않는다. 끌 수 있고, 방해 금지 시간에는 푸시만 보내지 않는다(ADR-093).
- **이 토글을 끄면(푸시·이메일 모두 끔) 이력도 남기지 않는다.** ADR-090의 시그널은 알림을 꺼도 이력에 남는다. 뉴스는 양이 많아
  끈 사람의 알림함까지 채우지 않는다. "전체 알림" 끄기와 채널 끄기는 발송만 막고 이력은 남긴다(다른 종류와 같다).
  이 판정은 SQL로 `notification_preferences` 행만 본다. 행이 없는 사용자의 옛 Redis 설정(지연 이전)은 발송 시점에만 적용된다.

**양 제한(`NewsAlertRules`, 팬아웃 시각 기준)**

| 규칙 | 값 | 넘으면 |
|---|---|---|
| 뉴스 신선도 | 발행 6시간 이내(미래 시각은 신선) | 아무것도 하지 않음 |
| 공시 신선도 | 접수일(KST 달력일) = 오늘(KST) | 아무것도 하지 않음 |
| 공시 중요도 | `importance_score >= 70`(사업·반기·분기보고서, 유상증자·자기주식, 합병·분할·인수) | 후보로 내지 않음 |
| 시간당 알림 상한 | 사용자당 최근 60분 `QUEUED` 5건 | 이력만(`CAPPED`), 알림 없음 |
| 시간당 이력 상한(뉴스만) | 사용자당 최근 60분 NEWS 이력 20건 | 기록하지 않음 |

- 공시는 중요도로 이미 걸렀고 드물어 이력 상한을 받지 않는다. 알림 상한은 뉴스와 같이 쓴다.
- 이력의 `triggered_at`은 **팬아웃 시각**이다(기사 발행 시각은 `payload_json.publishedAt`). 상한은 이 시각으로 센다.
- 상한을 세고 쓰는 사이에 다른 팬아웃이 끼지 않게 전역 advisory xact lock을 잡는다. 사용자별 잠금은 관심 사용자가 많은 종목에서
  공유 잠금 테이블(`max_locks_per_transaction`)을 다 쓸 수 있어 쓰지 않았다. 팬아웃은 짧고 수집은 이미 직렬(분산 락)이라 처리량 문제는 없다.

**스키마(V96)**: `alert_histories (user_id, category, triggered_at) WHERE user_id IS NOT NULL` 인덱스를 `CONCURRENTLY`로 만든다.
상한 계산이 사용자 이력 전체를 훑지 않게 한다. 열·제약은 바꾸지 않는다.

**웹**: 설정의 뉴스·공시 토글을 다시 켰다(푸시·이메일 칩, 켜진 알림 수에 포함). `/alerts`에 "뉴스·공시" 탭을 더하고 `NEWS`
(그리고 예전 규칙 종류 `NEWS_PUBLISHED`·`DISCLOSURE_PUBLISHED`)를 그 탭으로 옮겼다. 전달 상태 `CAPPED`를 "시간당 한도 초과 — 알림 없이 이력만"으로 보인다.

**배포 순서**: 어느 쪽을 먼저 배포해도 된다(ADR-094의 기본 순서는 api 먼저).
- api 먼저: V96 인덱스와 전달 채널 표시만 생긴다. api는 NEWS로 발행하지 않으니 이전 worker에 영향이 없다. 화면의 토글은 이미 있던 열에 저장된다.
- worker 먼저: V96이 없어도 같은 결과다(상한 계산이 느릴 뿐). 쓰는 열은 모두 V87 이전부터 있다.
- 새 내부 이벤트 두 개는 worker_outbox에만 기록된다. 롤링 배포 중 **이전 worker 인스턴스**의 재전송 주기가 새 이벤트 행을 만나면 클래스를
  해석하지 못해 그 주기가 실패할 수 있다(ADR-094 Context와 같은 현상). 배포가 끝나면 새 인스턴스가 처리한다. notify.user 와이어는 바뀌지 않는다.

## Reasons

- 수집과 같은 앱에서 팬아웃하면 새 토픽·소비자·DLT가 필요 없다. 이력 행과 색인은 worker가 이미 쓰는 경로(`AlertDispatcher`)와 같다.
- 발송을 같은 JVM 이벤트로 두면 롤링 배포의 버전 엇갈림이 구조적으로 없다. 그러면서도 발송 규칙은 notify.user 경로와 같은 디스패처·정책을 쓴다.
- 시간당 상한은 큐·스케줄러 없이 이력 테이블 카운트 하나로 된다. 넘친 건도 알림함에 남아(`CAPPED`) 정보를 잃지 않는다.
- 신선도를 팬아웃 시각에 판정하므로 백필·재전송 지연·첫 배포가 "지금 일어난 일" 푸시로 바뀌지 않는다.

## Consequences

- 받는 사람이 많은 종목(예: 시총 1위)의 기사 하나는 사용자 수만큼 행과 이벤트를 한 트랜잭션에 쓴다. 전역 잠금 아래라 그동안 다른 뉴스
  팬아웃은 기다린다. 수천 명까지는 문제없다(ADR-090과 같은 판단). 그 이상이면 묶음 단위로 나눈다 — 멱등이라 안전하다.
- 뉴스에는 중요도가 없어 상한 안에서는 모든 새 기사를 알린다. 첫 5건이 중요하지 않은 기사여도 그 시간의 나머지는 이력만 남는다.
- 기본값이 켜짐(V78)이라 이 배포 뒤 설정을 건드린 적 없는 관심종목 사용자도 받기 시작한다.
- 공시 접수일만 있어(시각 없음) 자정 직전 접수가 자정 뒤 수집되면 알리지 않는다. DART 접수 시간대상 드물다.
- 수집 쪽 `publishedAt`이 없으면(파싱 실패) 수집 시각으로 저장되므로 신선하다고 본다.
- 상한·신선도 값은 코드 상수다. 바꾸려면 배포가 필요하다.
- 이 기능 전의 뉴스·공시는 이력에 없다(백필하지 않음).

## Revisit When

- 사용자가 상한 때문에 놓친 알림에 불만을 보이거나 "아침 요약" 요구가 생길 때 — 요약 발송(ADR-093 Revisit과 함께)을 검토한다.
- 뉴스에 중요도 점수(AI 요약·관련도 등)가 생길 때 — 상한 대신 또는 상한과 함께 기준을 둔다.
- 종목당 관심 사용자가 1만 명을 넘거나 팬아웃 트랜잭션 시간이 알람 기준을 넘을 때 — 묶음 팬아웃·사용자별 잠금 범위 재검토.
- 사용자별 상한 설정(예: "시간당 n건")을 화면에서 고르게 할 때 — `notification_preferences`에 열을 더한다.
