/**
 * 주문 리플레이 재생 엔진(순수 함수) — 그날 분봉을 커서까지 잘라 보여 주고, 주문 시각으로 건너뛴다.
 * 커서는 "지금까지 보인 마지막 봉"의 인덱스다(-1 = 아직 아무것도 안 보임).
 */

/** 1× = 실제 1분을 0.5초에 — 하루(390분)가 약 3분. */
export const BASE_MS_PER_CANDLE = 500;

export interface Timed { time: number } // epoch seconds

/** t(초)가 속한 봉 인덱스 — t 이하인 마지막 봉. 첫 봉보다 이르면 0. */
export function candleIndexAt(candles: Timed[], t: number): number {
  if (candles.length === 0) return -1;
  let lo = 0, hi = candles.length - 1;
  if (t < candles[0].time) return 0;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (candles[mid].time <= t) lo = mid; else hi = mid - 1;
  }
  return lo;
}

/** 커서 다음에 오는 첫 주문의 봉 인덱스. 없으면 null. */
export function nextOrderIndex(candles: Timed[], orderTimes: number[], cursor: number): number | null {
  const idxs = orderTimes.map((t) => candleIndexAt(candles, t)).filter((i) => i > cursor).sort((a, b) => a - b);
  return idxs.length ? idxs[0] : null;
}

/** 한 틱에 진행할 봉 수 — 속도가 빨라도 화면은 최대 초당 ~8번만 다시 그린다. */
export function stepFor(speed: number): { candles: number; intervalMs: number } {
  const ms = BASE_MS_PER_CANDLE / speed;
  if (ms >= 125) return { candles: 1, intervalMs: ms };
  return { candles: Math.ceil(125 / ms), intervalMs: 125 };
}

/** 커서를 [steps]만큼 진행. 끝에 닿으면 마지막 인덱스에서 멈춘다. */
export function advance(cursor: number, steps: number, length: number): number {
  return Math.min(length - 1, cursor + steps);
}
