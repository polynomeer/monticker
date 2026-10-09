# ADR-101: 주간 투자 행동 리포트는 api가 월요일 아침 직접 메일로 보내고, (사용자, 주) 선점 행으로 한 번을 보장한다

## Status
Accepted

## Context

`/settings/notifications`의 "주간 투자 행동 리포트 (매주 월요일 · 이메일)"는 V78 `notification_preferences.weekly_report_email`(기본 true)로
저장만 되고 보내는 코드가 없었다. 그래서 PR #180에서 토글을 비활성(준비 중)으로 바꿨다. 이번에 실제로 보낸다.

정해야 할 것이 다섯이었다.

1. **어디서 보내나.**
   - (a) api가 `UserNotificationCommand`를 발행하고 worker `notify.user`가 이메일로 보낸다. 그런데 이 경로의 이메일은 짧은 평문이고
     (`SimpleMailMessage`), 정책표(ADR-093)의 종류·채널 규칙(푸시 우선·대체 이메일)을 거쳐야 한다. 주간 리포트는 이메일 전용이고 본문이 길다.
     메시지 한 건에 리포트 전체를 실으면 Kafka 메시지가 커지고, 한 번 보장이 Redis dedup(2일 TTL)에 걸린다.
   - (b) **api가 직접 `JavaMailSender`로 보낸다** — 채택. 지표 서비스(ADR-091)가 api에 있고, 이메일 인증 메일도 api가 보낸다.
2. **여러 파드·재시작에서 한 번.** 저장소의 스케줄 잡은 전역 락이 없다. 대신 결과 행의 고유 키로 한 번을 보장한다(ADR-070
   `risk_limit_warnings`의 `INSERT … ON CONFLICT DO NOTHING`, ADR-059 결정적 orderId). worker에는 Redis `@DistributedLock`이 있지만
   api에는 없고, 실행 전체를 감싸는 락은 오래 도는 잡이 커넥션·락을 오래 붙잡는다.
3. **메일 서버 실패 재시도와 중복.** SMTP는 트랜잭션이 아니다. "보냈는데 기록 전에 죽음"과 "안 보냄"을 구별할 수 없다.
4. **규모.** 사용자 전체를 한 번에 읽거나 실행 시간이 끝없이 늘면 안 된다. 스케줄러 스레드는 기본 1개이고 모든 `@Scheduled`가 같이 쓴다.
5. **방해 금지 시간.** ADR-093은 "이메일은 방해 금지 대상이 아니다"로 정했다.

## Decision

### 대상과 시각

- **대상 주**: 실행 시각이 속한 KST 주의 **직전 주**(월 00:00 ~ 다음 월 00:00 KST). `ReportWeek.at(now)`가 Kotlin `KstPeriod`로 계산한다.
- **대상 사용자**: 탈퇴하지 않음, 이메일 인증함, **전체 알림·이메일 채널·주간 리포트 모두 켜짐**(`ReportPreference.wantsReport`),
  대상 주에 모의 체결(`paper_trades`)이 1건 이상. 설정 행이 없으면 기본값(켜짐)이다. 다만 옛 Redis 설정(ADR-082 지연 이전)은 페이지마다
  `MGET` 한 번으로 확인한다. Redis가 실패하면 그 사용자들은 이번 실행에서 보내지 않고 다음 실행에 다시 본다.
- **시각**: 월요일 08:00~20:30 KST, 30분마다(`app.weekly-report.cron`). 08:00 실행이 대부분을 보낸다. 뒤 실행들은 상한을 넘긴 나머지와
  재시도, 그리고 08:00에 떠 있지 않던 파드의 몫을 이어 받는다. 보낸 사용자는 후보 쿼리에서 빠지므로 빈 실행은 쿼리 한 번이다.
- **방해 금지 시간은 보지 않는다**(ADR-093 그대로 — 이메일은 소리가 나지 않는다). 공유 정책표에는 종류를 더하지 않았다. 이 리포트는 `notify.user`의
  푸시·대체 이메일 정책을 거치지 않는 이메일 전용 경로이기 때문이다.

### 한 번 보장 — V97 `weekly_report_sends`

```
PK (user_id, week_start)   status: SENDING | SENT | FAILED | SKIPPED   attempts, claimed_at, sent_at, next_attempt_at, last_error

선점:  INSERT (SENDING, attempts 1) ON CONFLICT DO UPDATE SET SENDING, attempts+1
         WHERE status='FAILED' AND attempts < 3 AND next_attempt_at <= now   RETURNING attempts
       → 행이 돌아온 인스턴스만 보낸다(여러 파드가 같은 사용자를 동시에 봐도 한 곳)
보냄:  SMTP 수락 → SENT (선점한 그 attempts일 때만 갱신)
실패:  보내기 전 실패(DB 등)·SMTP 거부/연결 실패/인증 실패 → FAILED, next_attempt_at = now + 30분 × attempts (최대 3회)
       메시지 생성 실패·수신 주소 거부(영구) → FAILED, next_attempt_at NULL (재시도 없음)
SENDING으로 남은 행(선점 후 파드가 죽음) → 다시 보내지 않는다
```

- 전역 실행 락은 두지 않는다. 파드 여럿이 동시에 돌면 사용자 단위 선점으로 일을 나눠 갖는다. ADR-070과 같은 방식이다.
- 선점과 결과 기록은 각각 짧은 단일 문장이다. 메일 발송 동안 트랜잭션·락을 잡지 않는다.
- `last_error`에는 예외 클래스 이름만 남긴다(메시지에 수신 주소가 들어갈 수 있다). 기록은 12주 보관한다.

### 범위·실행

- 후보는 사용자 id **키셋 페이지**(200명)로 한 쿼리씩 읽는다(설정·sent-log·체결 존재를 JOIN/EXISTS로 한 번에). 실행 한 번은
  **2000명·20분**까지이고 종료 신호를 받으면 다음 사용자로 넘어가지 않는다. 남은 사용자는 30분 뒤 실행이 이어 받는다.
- 사용자별 지표 계산은 ADR-091 서비스를 **그대로** 부른다: `ScoreDetailService.forWeek`(점수 카드 `weekly`가 같은 함수를 쓰도록 나눴다),
  `EmotionTagService.getAnalysis(period)`. 사용자당 쿼리 수는 고정이다(체결 수와 무관). 정의가 하나라서 리포트 숫자는 /wallet 화면의 숫자와 같다.
- `@Async("weeklyReportExecutor")`(스레드 1, 대기열 0)에서 돈다. 공용 스케줄러 스레드를 붙잡지 않는다. 같은 파드에서 이미 돌고 있으면
  새 트리거는 버린다.
- 지표: `weekly_behavior_report_total{outcome=sent|failed|skipped|not_claimed, reason}`, `weekly_behavior_report_run`(타이머).
  로그는 userId·주·건수만 남긴다(이메일 주소 없음).

### 내용 — 행동 피드백만

대상 주의 모의 체결 수(매수·매도, 전주 대비), 계획 준수율, 손절 준수율(비율·분자/분모·전주 대비 %p), 행동 점수 주간 평균(전주 대비),
감정 분포(태그 건수·비중). **종목명·가격·수익률·매매 제안은 넣지 않는다.** 분모가 0이면 "—"로 쓴다. 지어낸 숫자는 보이지 않는다.
닉네임은 HTML 이스케이프한다. 끝에는 지갑 링크, 수신 거부 안내(로그인 없는 수신 거부 링크와 알림 설정 링크, `List-Unsubscribe`·
`List-Unsubscribe-Post` 헤더 — ADR-102), 표준 고지
("monticker는 투자자문·투자중개업자가 아니며, 제공 정보는 투자 권유가 아닙니다.")를 붙인다. 평문·HTML 두 본문을 함께 보낸다.

## Reasons

- 고유 키 선점은 이미 쓰는 방식이다(ADR-070). 락이 풀리는 시점이나 TTL에 기대지 않고 DB가 한 번을 보장한다. 파드가 늘면 일을 나눠 갖는다.
- SMTP 결과를 모르는 경우에는 **누락을 택한다**. 같은 주간 리포트가 두 통 오면 신뢰를 잃는다. 한 통이 빠지면 지갑 화면에 같은 숫자가 있다.
  확실히 안 보낸 실패만 재시도한다.
- 지표 정의를 복제하지 않는다. 점수 카드와 같은 함수에 주만 바꿔 넣는다.
- 페이지·상한·시간 예산이 있어 사용자 수가 늘어도 실행 하나가 끝없이 길어지지 않는다. 남은 사용자는 다음 실행이 받는다.

## Consequences

- 선점 직후 파드가 죽으면 그 사용자는 그 주 리포트를 받지 못한다(SENDING 고착). 운영에서 SENDING 행 수로 확인할 수 있다.
- SMTP가 받은 뒤 응답이 끊겨 예외가 난 드문 경우에는 재시도가 두 번째 메일이 될 수 있다(SMTP의 한계 — 보내지 않았다고 판단한 실패만 재시도한다).
- 사용자당 지표 계산 쿼리가 7개 안팎이다. 상한 2000명 × 30분 간격 × 26회면 월요일 하루에 약 5만 명이다. 이보다 커지면 상한·간격을
  늘리거나 지표를 미리 계산하는 배치로 바꾼다.
- ~~수신 거부는 로그인 후 설정 화면에서 한다. 로그인 없는 한 번 클릭 수신 거부(서명 토큰)는 없다.~~ → [ADR-102](102-one-click-unsubscribe.md)로
  로그인 없는 원클릭 수신 거부(RFC 8058, 서명 토큰)를 더했다. 설정 화면 링크는 보조로 남는다.
- 개발 환경은 MailHog로 받는다. `app.weekly-report.enabled=false`로 끌 수 있다.
- 웹 알림 설정의 "켜진 알림" 수에 주간 리포트를 다시 센다.

## Revisit When

- 사용자가 많아 월요일 하루에 다 못 보낼 때 — 지표 사전 계산(일별 스냅샷, ADR-091 Revisit)이나 별도 발송 큐로 옮긴다.
- ~~로그인 없는 한 번 클릭 수신 거부가 법무·스팸 정책상 필요해질 때~~ — [ADR-102](102-one-click-unsubscribe.md)에서 반영.
- api에 공용 분산 락(또는 ShedLock)이 생길 때 — 실행 단위 락을 더할지 검토한다(선점 행은 그대로 둔다).
- 사용자 시간대 설정이 생길 때 — "월요일 아침"과 주 경계를 사용자 시간대로 바꿀지 정한다.
