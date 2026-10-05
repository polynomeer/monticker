/** 일봉 candle_time(초) → KST 거래일 "YYYY-MM-DD". candles_1d는 KST 자정에 찍힌다(CandleAggregator). */
export function kstDayKey(epochSec: number) {
  return new Date((epochSec + 9 * 3600) * 1000).toISOString().slice(0, 10);
}

/** 베타 계산에 필요한 최소 공통 수익률 개수 — 이보다 적으면 숫자를 내지 않는다 */
export const MIN_BETA_POINTS = 20;

/**
 * 종목의 지수 대비 베타 = Cov(종목 일간 수익률, 지수 일간 수익률) / Var(지수 일간 수익률).
 * 두 시계열에 모두 있는 거래일만 쓰고, 그 날짜 순서대로 수익률을 만든다(휴장일이 달라도 같은 구간끼리 비교).
 */
export function beta(stock: Map<string, number>, index: Map<string, number>): number | null {
  const days = Array.from(stock.keys()).filter((d) => index.has(d)).sort();
  const rs: number[] = [];
  const ri: number[] = [];
  for (let i = 1; i < days.length; i++) {
    const s0 = stock.get(days[i - 1])!, s1 = stock.get(days[i])!;
    const i0 = index.get(days[i - 1])!, i1 = index.get(days[i])!;
    if (!s0 || !i0) continue;
    rs.push(s1 / s0 - 1);
    ri.push(i1 / i0 - 1);
  }
  const n = rs.length;
  if (n < MIN_BETA_POINTS) return null;
  const ms = rs.reduce((a, v) => a + v, 0) / n;
  const mi = ri.reduce((a, v) => a + v, 0) / n;
  let cov = 0, vari = 0;
  for (let k = 0; k < n; k++) {
    cov += (rs[k] - ms) * (ri[k] - mi);
    vari += (ri[k] - mi) ** 2;
  }
  return vari > 0 ? cov / vari : null;
}
