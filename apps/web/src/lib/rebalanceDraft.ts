// /analytics → /brokerage/rebalance 로 추천 비중을 "초안"으로 넘기는 통로.
//
// 실거래 화면이라 서버에 아무것도 저장하지 않는다. 같은 탭의 sessionStorage에 한 번만 읽히는
// 초안을 두고, 리밸런싱 화면은 이것을 편집 중(저장 안 됨) 상태로 채우기만 한다. 목표 비중 저장·
// 괴리 미리보기·실행 확인은 기존 리밸런싱 화면의 단계와 서버 검증(ADR-034, V-M6)을 그대로 거친다.
// 종목·비중은 URL에 싣지 않는다(공유·기록에 남지 않게).

const KEY = "monticker.rebalanceDraft.v1";
/** 오래된 초안은 버린다 — 다른 날 분석 결과가 갑자기 채워지지 않게. */
const MAX_AGE_MS = 30 * 60_000;
const MAX_ROWS = 20;

export interface RebalanceDraftRow {
  symbol: string;
  name: string;
  stockId: number;
  /** % 단위, 소수 1자리 */
  weightPct: number;
}

export interface RebalanceDraft {
  createdAt: number;
  rows: RebalanceDraftRow[];
  expectedReturn: number;
  expectedRisk: number;
  suggestion: string;
}

/**
 * 0~1 비중을 % 소수 1자리로 반올림한다. 반올림으로 합이 100을 넘으면(예: 100.1) 리밸런싱 저장
 * 검증에 막히므로 가장 큰 비중에서 초과분을 덜어 낸다. 0.0%가 되는 종목은 뺀다 — 서버가 0 이하
 * 비중을 거부한다(RebalanceTargetService).
 */
export function toDraftWeights(weights: { stockId: number; weight: number }[]): { stockId: number; weightPct: number }[] {
  const rounded = weights
    .filter((w) => Number.isFinite(w.weight) && w.weight > 0)
    .map((w) => ({ stockId: w.stockId, weightPct: Math.round(w.weight * 1000) / 10 }))
    .filter((w) => w.weightPct > 0);
  const overflow = Math.round((rounded.reduce((a, w) => a + w.weightPct, 0) - 100) * 10) / 10;
  if (overflow > 0 && rounded.length > 0) {
    const max = rounded.reduce((a, w) => (w.weightPct > a.weightPct ? w : a));
    max.weightPct = Math.round((max.weightPct - overflow) * 10) / 10;
  }
  return rounded;
}

function isValid(d: unknown): d is RebalanceDraft {
  if (!d || typeof d !== "object") return false;
  const x = d as RebalanceDraft;
  if (typeof x.createdAt !== "number" || !Array.isArray(x.rows)) return false;
  if (x.rows.length === 0 || x.rows.length > MAX_ROWS) return false;
  const ok = x.rows.every((r) =>
    typeof r.symbol === "string" && r.symbol.length > 0 && typeof r.name === "string" &&
    Number.isInteger(r.stockId) && typeof r.weightPct === "number" && r.weightPct > 0 && r.weightPct <= 100,
  );
  const total = x.rows.reduce((a, r) => a + r.weightPct, 0);
  return ok && total <= 100.0001 && new Set(x.rows.map((r) => r.symbol)).size === x.rows.length;
}

export function saveRebalanceDraft(draft: RebalanceDraft): boolean {
  if (!isValid(draft)) return false;
  try {
    sessionStorage.setItem(KEY, JSON.stringify(draft));
    return true;
  } catch {
    return false;
  }
}

/** 한 번 읽으면 지운다. 없거나 깨졌거나 오래됐으면 null. */
export function takeRebalanceDraft(now = Date.now()): RebalanceDraft | null {
  let raw: string | null = null;
  try {
    raw = sessionStorage.getItem(KEY);
    sessionStorage.removeItem(KEY);
  } catch {
    return null;
  }
  if (!raw) return null;
  try {
    const d: unknown = JSON.parse(raw);
    if (!isValid(d)) return null;
    if (now - d.createdAt > MAX_AGE_MS || d.createdAt > now + 60_000) return null;
    return d;
  } catch {
    return null;
  }
}
