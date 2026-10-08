-- ADR-099: 관심 분야를 홈·알림 화면의 "정렬 신호"로만 쓴다. 사용자가 끌 수 있는 스위치("관심 분야 순")를 계정에 둔다.
--  - 기본 TRUE: 관심 분야를 고른 사용자에게만 효과가 있다(고른 분야가 없으면 화면이 무시한다).
--  - 푸시 발송량·방해 금지 시간·전달 정책(backend/contracts/notification-delivery-policy.json)과는 무관하다.
--  - PG11+ fast default — 테이블 재작성 없음.
ALTER TABLE user_preferences
    ADD COLUMN interest_ordering BOOLEAN NOT NULL DEFAULT TRUE;
