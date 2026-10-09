const API = "";

export type UnsubscribeResult = "done" | "invalid" | "retry";

/**
 * ADR-102 — 이메일 원클릭 수신 거부(로그인 없음). 메일 클라이언트가 RFC 8058로 보내는 것과 같은 요청을 보낸다:
 * 토큰은 쿼리, 본문은 `List-Unsubscribe=One-Click`. 로그인 토큰(Authorization)·쿠키는 보내지 않는다.
 *
 * - 200 → done(이미 꺼져 있어도 200이다)
 * - 400 → invalid(서명이 틀렸거나 잘린 링크 — 다시 눌러도 같다)
 * - 그 밖(429·503·네트워크) → retry
 */
export async function unsubscribeWithToken(token: string): Promise<UnsubscribeResult> {
  try {
    const res = await fetch(`${API}/api/unsubscribe?token=${encodeURIComponent(token)}`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: "List-Unsubscribe=One-Click",
      credentials: "omit",
      cache: "no-store",
    });
    if (res.ok) return "done";
    if (res.status === 400) return "invalid";
    return "retry";
  } catch {
    return "retry";
  }
}
