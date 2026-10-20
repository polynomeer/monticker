const DEV_API_BASE = "http://localhost:8080";
const DEV_WEB_BASE = "http://localhost:3000";

/**
 * 릴리스 빌드는 https만 쓴다 — 비밀번호와 refresh token이 오가므로, 설정을 빠뜨린 빌드가 평문으로 보내지 않게 앱을 멈춘다.
 * EXPO_PUBLIC_* 값은 빌드할 때 박히므로 실행 중에 바꿀 수 없다.
 */
function requireHttps(url: string, name: string): string {
  if (!__DEV__ && !url.startsWith("https://")) throw new Error(`${name}는 https 주소여야 합니다: ${url}`);
  return url;
}

export function getApiBase(): string {
  return requireHttps(process.env.EXPO_PUBLIC_API_URL ?? DEV_API_BASE, "EXPO_PUBLIC_API_URL");
}

/** 웹 주소 — 회원가입·비밀번호 찾기·파일 가져오기처럼 웹에서만 하는 일로 보낼 때 */
export function getWebBase(): string {
  return requireHttps(process.env.EXPO_PUBLIC_WEB_URL ?? DEV_WEB_BASE, "EXPO_PUBLIC_WEB_URL");
}
