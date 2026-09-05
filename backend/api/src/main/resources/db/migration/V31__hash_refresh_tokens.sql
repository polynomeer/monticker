-- Refresh token은 지금까지 평문 JWT로 저장됐다 — DB가 유출되면 그 자체로 바로 사용 가능한
-- 자격증명이 노출된다(비밀번호는 이미 해시 저장 중인데 refresh token만 예외였음).
-- 앞으로는 SHA-256 해시만 저장한다. 기존에 발급된 토큰은 새 해시 스킴과 일치하지 않으므로
-- 자동 무효화된다 — 아직 실사용자 데이터가 없는 단계라 재로그인을 요구하는 것으로 충분하다.
ALTER TABLE refresh_tokens RENAME COLUMN token TO token_hash;
