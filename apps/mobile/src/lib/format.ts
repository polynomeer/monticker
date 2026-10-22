/** 숫자 표시 — 화면마다 같은 규칙. 순수 함수라 노드 테스트로 확인한다 */

export function formatPct(value: number, digits = 2): string {
  const sign = value > 0 ? "+" : value < 0 ? "-" : "";
  return `${sign}${Math.abs(value).toFixed(digits)}%`;
}

/** 원화 정수 금액(천 단위 쉼표) */
export function formatWon(value: number): string {
  return Math.round(value).toLocaleString("ko-KR");
}

/** 손익처럼 부호가 뜻이 있는 금액 */
export function formatSignedWon(value: number): string {
  const rounded = Math.round(value);
  return `${rounded > 0 ? "+" : rounded < 0 ? "-" : ""}${Math.abs(rounded).toLocaleString("ko-KR")}`;
}

/** 시장에 맞는 가격 — 해외 종목은 달러 소수 둘째 자리 */
export function formatPrice(value: number, currency: "KRW" | "USD" = "KRW"): string {
  if (currency === "USD") return `$${value.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  return formatWon(value);
}

/** 값 배열 → 상자 안 좌표(위가 큰 값). 모두 같으면 가운데 수평선 */
export function sparklinePoints(values: number[], width: number, height: number, pad = 2): [number, number][] {
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min;
  const step = values.length > 1 ? width / (values.length - 1) : 0;
  return values.map((v, i) => {
    const y = span === 0 ? height / 2 : pad + (1 - (v - min) / span) * (height - pad * 2);
    return [round1(i * step), round1(y)];
  });
}

function round1(n: number): number {
  return Math.round(n * 10) / 10;
}
