-- ADR-025: 실거래 주문 안전장치 + KIS 클라이언트 버그 수정

-- appKey/appSecret을 저장하지 않으면 토큰 발급 이후의 모든 KIS 인증 호출이
-- appkey/appsecret 헤더 누락으로 거부된다 — access_token과 동일하게 암호화 저장한다.
ALTER TABLE brokerage_accounts ADD COLUMN app_key TEXT;
ALTER TABLE brokerage_accounts ADD COLUMN app_secret TEXT;

-- 페이퍼/실거래 리스크 체크 로그를 구분한다.
ALTER TABLE risk_check_logs ADD COLUMN account_type VARCHAR(10) NOT NULL DEFAULT 'PAPER';
