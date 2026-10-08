/**
 * `/login?error=...` 코드 → 고정 문구. 쿼리 값은 화면에 그대로 찍지 않는다(누구나 링크로 임의 문구를 띄울 수 있다 —
 * "고객센터 010-…로 연락하세요" 같은 피싱 문구). 아는 코드만 해당 문구, 모르는 코드는 일반 문구.
 *
 * 코드를 보내는 곳: 백엔드 SecurityConfig `failureUrl(/login?error=oauth2)`, OAuth2SuccessHandler(이메일 없음),
 * /oauth2/callback(토큰 없음·오류 전달).
 */
const MESSAGES: Record<string, string> = {
  oauth2: "소셜 로그인에 실패했습니다. 잠시 후 다시 시도하거나 다른 방법으로 로그인해주세요.",
};

const GENERIC = "로그인 중 문제가 발생했습니다. 다시 시도해주세요.";

export function loginErrorMessage(code: string | null | undefined): string | null {
  if (!code) return null;
  return Object.prototype.hasOwnProperty.call(MESSAGES, code) ? MESSAGES[code] : GENERIC;
}
