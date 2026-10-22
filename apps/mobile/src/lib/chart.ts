/** 종목 상세 차트 — 기간별 캔들 요청과 이동평균. 순수 함수 */

export type Period = "1m" | "15m" | "1d" | "1w" | "1M" | "1y";

export const PERIODS: { value: Period; label: string }[] = [
  { value: "1m", label: "1분" },
  { value: "15m", label: "15분" },
  { value: "1d", label: "일" },
  { value: "1w", label: "주" },
  { value: "1M", label: "월" },
  { value: "1y", label: "1년" },
];

/** 서버에 1년 간격이 없어 일봉 + from 365일로 그린다 */
export function candleQuery(period: Period, now: Date): string {
  if (period === "1y") {
    const from = new Date(now.getTime() - 365 * 86_400_000).toISOString();
    return `interval=1d&from=${encodeURIComponent(from)}`;
  }
  return `interval=${period}`;
}

/** 단순 이동평균 — 앞쪽 n-1개는 null */
export function movingAverage(values: number[], n: number): (number | null)[] {
  const out: (number | null)[] = [];
  let sum = 0;
  values.forEach((v, i) => {
    sum += v;
    if (i >= n) sum -= values[i - n];
    out.push(i >= n - 1 ? sum / n : null);
  });
  return out;
}

/** 값 → 차트 y(위가 큰 값). 범위는 종가·이평 모두를 담는다 */
export function scaleY(min: number, max: number, height: number, pad: number) {
  const span = max - min || 1;
  return (v: number) => pad + (1 - (v - min) / span) * (height - pad * 2);
}
