/**
 * 조건부 주문 유효 기간 — 서버(ConditionalOrderService.expiryFor)와 같은 규칙: N일 = KST 날짜로 오늘+N일까지 유효,
 * 그다음 날 00:00 KST에 만료. 브라우저 시간대와 무관하게 KST 날짜로 계산한다.
 */
export const VALID_DAYS_OPTIONS = [1, 7, 30, 60, 90] as const;
export const DEFAULT_VALID_DAYS = 90;
export const MIN_VALID_DAYS = 1;
export const MAX_VALID_DAYS = 90;

const KST_OFFSET_MS = 9 * 3_600_000;
const DAY_MS = 86_400_000;

/** KST 기준 오늘+N일의 날짜(UTC 자정으로 표현한 Date — 연·월·일만 쓴다). */
function kstDatePlus(days: number, now: number): Date {
  const kstMidnight = Math.floor((now + KST_OFFSET_MS) / DAY_MS) * DAY_MS;
  return new Date(kstMidnight + days * DAY_MS);
}

/** "10/15" 형식의 마지막 유효일(KST). */
export function lastValidDateLabel(days: number, now = Date.now()): string {
  const d = kstDatePlus(days, now);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getUTCMonth() + 1)}/${p(d.getUTCDate())}`;
}

/** 서버가 저장할 만료 시각(그다음 날 00:00 KST)을 ISO로 — 표시·테스트용. */
export function expiryInstant(days: number, now = Date.now()): string {
  return new Date(kstDatePlus(days + 1, now).getTime() - KST_OFFSET_MS).toISOString();
}

export function isValidDays(days: number): boolean {
  return Number.isInteger(days) && days >= MIN_VALID_DAYS && days <= MAX_VALID_DAYS;
}
