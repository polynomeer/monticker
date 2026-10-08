/**
 * 드로잉 도구의 순수 계산 — 측정, 자석, 확대 구간, 간격 변경 시 표시 여부, 하이킨아시.
 * 차트 라이브러리와 무관하다.
 */
import type { CandleData, ChartInterval, Drawing, DrawingPoint } from "./types";
import { bucketOf } from "./tradeMarkers";

export interface IndexedPoint {
  /** 봉 인덱스(소수 가능) */
  index: number;
  price: number;
}

export interface MeasureResult {
  priceDiff: number;
  /** 시작 가격 대비 변화율(%). 시작 가격이 0 이하면 null */
  pct: number | null;
  /** 두 점 사이 봉 개수(끝점 차이) */
  bars: number;
}

export function measure(a: IndexedPoint, b: IndexedPoint): MeasureResult {
  const priceDiff = b.price - a.price;
  return {
    priceDiff,
    pct: a.price > 0 ? (priceDiff / a.price) * 100 : null,
    bars: Math.round(Math.abs(b.index - a.index)),
  };
}

function fmtAbs(v: number): string {
  const a = Math.abs(v);
  return a >= 100
    ? a.toLocaleString("ko-KR", { maximumFractionDigits: 0 })
    : a.toLocaleString("ko-KR", { maximumFractionDigits: 2 });
}

/** "+1,200 (+2.35%) · 12봉" */
export function formatMeasure(m: MeasureResult): string {
  const sign = m.priceDiff > 0 ? "+" : m.priceDiff < 0 ? "−" : "";
  const pct = m.pct == null ? "—" : `${m.pct > 0 ? "+" : m.pct < 0 ? "−" : ""}${Math.abs(m.pct).toFixed(2)}%`;
  return `${sign}${fmtAbs(m.priceDiff)} (${pct}) · ${m.bars}봉`;
}

/** 자석 — 가격을 그 봉의 시·고·저·종 중 가장 가까운 값으로 붙인다. 봉이 없으면 그대로 */
export function magnetSnap(candle: Pick<CandleData, "open" | "high" | "low" | "close"> | undefined, price: number): number {
  if (!candle) return price;
  let best = candle.open;
  for (const v of [candle.high, candle.low, candle.close]) {
    if (Math.abs(v - price) < Math.abs(best - price)) best = v;
  }
  return best;
}

/**
 * 두 점 사이로 확대할 봉 인덱스 구간. 정렬·반올림·[0, n-1]로 자르고, 최소 minBars개 봉이 보이게 넓힌다.
 */
export function zoomWindow(a: number, b: number, n: number, minBars = 2): { startValue: number; endValue: number } | null {
  if (n <= 0 || !Number.isFinite(a) || !Number.isFinite(b)) return null;
  const clamp = (v: number) => Math.max(0, Math.min(n - 1, Math.round(v)));
  let start = clamp(Math.min(a, b));
  let end = clamp(Math.max(a, b));
  const need = Math.min(n, Math.max(1, minBars)) - 1;
  while (end - start < need) {
    if (end < n - 1) end += 1;
    if (end - start < need && start > 0) start -= 1;
  }
  return { startValue: start, endValue: end };
}

/**
 * 현재 봉 간격에서 이 드로잉을 그릴 의미가 있는가.
 * 수평선·텍스트는 항상 그린다. 여러 시각에 걸친 선(추세선·펜)은 지금 간격에서 모든 점이 한 봉에
 * 몰리면(예: 1분봉에서 그린 짧은 선을 일봉에서 볼 때) 수직선으로 뭉개지므로 그리지 않는다.
 */
export function isDrawingMeaningful(d: Pick<Drawing, "tool" | "points">, interval: ChartInterval): boolean {
  if (d.tool === "HORIZONTAL_LINE" || d.tool === "TEXT") return true;
  const times = new Set(d.points.map((p) => p.time));
  if (times.size < 2) return true;
  const buckets = new Set(d.points.map((p) => bucketOf(p.time, interval)));
  return buckets.size > 1;
}

/** 하이킨아시 봉 — 표시용. 시각·거래량은 원래 봉 그대로 */
export function heikinAshi(candles: CandleData[]): CandleData[] {
  const out: CandleData[] = [];
  candles.forEach((c, i) => {
    const close = (c.open + c.high + c.low + c.close) / 4;
    const open = i === 0 ? (c.open + c.close) / 2 : (out[i - 1].open + out[i - 1].close) / 2;
    out.push({
      time: c.time,
      open,
      close,
      high: Math.max(c.high, open, close),
      low: Math.min(c.low, open, close),
      volume: c.volume,
    });
  });
  return out;
}

/** 점 목록을 max개 이하로 고르게 솎는다(양 끝점 유지) — 펜 획 저장 크기 상한용 */
export function decimate<T>(points: T[], max: number): T[] {
  if (points.length <= max || max < 2) return points.slice(0, Math.max(0, max));
  const out: T[] = [];
  const step = (points.length - 1) / (max - 1);
  for (let i = 0; i < max; i++) out.push(points[Math.round(i * step)]);
  return out;
}

/** 드로잉 전체를 (봉 인덱스, 가격) 만큼 옮긴 새 점 목록 — 끌어서 옮기기 */
export function shiftPoints(
  points: DrawingPoint[],
  toIndex: (t: number) => number,
  toTime: (index: number) => number,
  dIndex: number,
  shiftPrice: (price: number) => number,
): DrawingPoint[] {
  return points.map((p) => ({ time: toTime(toIndex(p.time) + dIndex), price: shiftPrice(p.price) }));
}
