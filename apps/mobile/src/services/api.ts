const DEV_API_BASE = "http://localhost:8080";
const DEV_WEB_BASE = "http://localhost:3000";

export function getApiBase(): string {
  return process.env.EXPO_PUBLIC_API_URL ?? DEV_API_BASE;
}

/** 웹 주소 — 회원가입·비밀번호 찾기·파일 가져오기처럼 웹에서만 하는 일로 보낼 때 */
export function getWebBase(): string {
  return process.env.EXPO_PUBLIC_WEB_URL ?? DEV_WEB_BASE;
}
