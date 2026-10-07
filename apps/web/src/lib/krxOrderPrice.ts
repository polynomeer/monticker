/**
 * ADR-081 — KRX 지정가 규칙(서버 OrderPriceGuard·KrxPriceRules와 같은 계산). 화면은 미리 알려 줄 뿐이고, 판정은 서버가 한다.
 */

/** KRX 호가 단위(2023년 개편, 유가·코스닥 공통). */
export function krxTick(price: number): number {
  if (price < 2_000) return 1;
  if (price < 5_000) return 5;
  if (price < 20_000) return 10;
  if (price < 50_000) return 50;
  if (price < 200_000) return 100;
  if (price < 500_000) return 500;
  return 1_000;
}

/** 원 단위 양의 정수이고 그 가격대 호가 단위의 배수인가. */
export function isOnKrxTick(price: number): boolean {
  return Number.isInteger(price) && price > 0 && price % krxTick(price) === 0;
}

/** 국내 6자리 종목 코드인가(호가 단위·가격제한폭 대상). */
export const isKrxSymbol = (s: string) => /^\d{5}[0-9A-Z]$/.test(s);

/** 전일 종가 기준 ±30%의 바깥 경계. 이 밖은 서버가 거부한다(서버가 기준가를 확인할 수 있을 때). */
export function krxBand(prevClose: number): { low: number; high: number } {
  return { low: Math.ceil(prevClose * 0.7), high: Math.floor(prevClose * 1.3) };
}

/** 등락률 표시(+1.25% / −0.40%). 모르면 null. */
export function fmtChangeRate(rate: number | null | undefined): string | null {
  if (rate == null || Number.isNaN(rate)) return null;
  const sign = rate > 0 ? "+" : rate < 0 ? "−" : "";
  return `${sign}${Math.abs(rate).toFixed(2)}%`;
}
