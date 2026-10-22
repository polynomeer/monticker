/**
 * 실전 주문 시트 계산(ADR-113·025). 실전 판단에는 모의 데이터를 쓰지 않는다 — 집중도만 증권사 잔고로 화면에서 미리 보고,
 * 일간 손실·주문 빈도는 서버 게이트가 판정한다. 순수 함수라 노드 테스트로 확인한다.
 */

export interface LiveHolding { symbol: string; quantity: number; currentPrice: number }

/** 주문 뒤 이 종목이 총자산에서 차지할 비중(%). 총자산을 모르면 null */
export function concentrationAfter(
  totalEvaluated: number, holdings: LiveHolding[], symbol: string, side: "BUY" | "SELL", quantity: number, price: number,
): number | null {
  if (!(totalEvaluated > 0)) return null;
  const held = holdings.find((h) => h.symbol === symbol);
  const qty = (held?.quantity ?? 0) + (side === "BUY" ? quantity : -quantity);
  const unit = price > 0 ? price : held?.currentPrice ?? 0;
  return (Math.max(0, qty) * unit / totalEvaluated) * 100;
}

export type LiveOutcome =
  | { kind: "accepted"; message: string }
  | { kind: "rejected"; message: string }
  /** 증권사에 갔는지 모른다 — 다시 보내지 않고 주문 내역에서 확인한다 */
  | { kind: "unknown"; message: string };

const UNKNOWN_MESSAGE = "주문 결과를 아직 확인하지 못했어요. 다시 보내지 말고 내 계좌 › 실전 › 주문에서 체결 여부를 확인해 주세요.";

/**
 * 증권사로 나가기 전에 서버가 막았다고 확신할 수 있는 상태만. 그 밖의 4xx(408·409·425, 프록시의 4xx 등)는
 * 증권사에 닿았는지 알 수 없으므로 결과 불명으로 본다 — 사용자가 새 키로 다시 보내 중복 주문이 나는 것을 막는다.
 */
const DEFINITE_4XX = new Set([400, 401, 403, 404, 422, 423, 429]);

/**
 * 서버 응답 → 화면 결과. 확정 거절(리스크 422, 거래 중지 423 등)만 다시 시도할 수 있다.
 * 5xx·연결 끊김·알 수 없는 4xx·본문 없는 2xx·UNKNOWN 상태는 결과 불명이다.
 */
export function classifyLiveResult(
  httpStatus: number | null,
  body: { status?: string; rejectReason?: string | null; needsReview?: boolean; message?: string } | null,
): LiveOutcome {
  if (httpStatus == null || httpStatus >= 500) return { kind: "unknown", message: UNKNOWN_MESSAGE };
  if (httpStatus >= 400) {
    if (!DEFINITE_4XX.has(httpStatus)) return { kind: "unknown", message: UNKNOWN_MESSAGE };
    const reason = body?.message ?? "주문을 보내지 못했어요.";
    if (httpStatus === 422) return { kind: "rejected", message: `리스크 한도에 걸려 주문하지 않았어요. ${reason}` };
    if (httpStatus === 429) return { kind: "rejected", message: "주문이 너무 잦아요. 잠시 뒤 다시 시도해 주세요." };
    return { kind: "rejected", message: reason };
  }
  if (!body || body.needsReview) return { kind: "unknown", message: UNKNOWN_MESSAGE };
  switch (body.status) {
    case "REJECTED": return { kind: "rejected", message: `증권사가 주문을 거절했어요.${body.rejectReason ? ` ${body.rejectReason}` : ""}` };
    case "CANCELLED": return { kind: "rejected", message: "주문이 취소됐어요." };
    case "FILLED": return { kind: "accepted", message: "체결됐어요." };
    case "PARTIALLY_FILLED": return { kind: "accepted", message: "일부 체결됐어요. 나머지는 내 계좌 › 실전 › 주문에서 볼 수 있어요." };
    case "SUBMITTED": return { kind: "accepted", message: "증권사에 주문을 보냈어요. 체결되면 알려 드려요." };
    default: return { kind: "unknown", message: UNKNOWN_MESSAGE };
  }
}

/** 지정가가 현재가에서 몇 % 떨어졌는지(잘못 누른 가격 경고용). 현재가를 모르면 null */
export function priceDeviationPct(limitPrice: number, current: number): number | null {
  if (!(current > 0) || !(limitPrice > 0)) return null;
  return ((limitPrice - current) / current) * 100;
}

/** 이만큼 넘게 떨어진 지정가는 경고한다 */
export const DEVIATION_WARN_PCT = 10;

/** 길게 누르기 시간 — 실수로 스치는 탭으로는 주문이 나가지 않게 */
export const LONG_PRESS_MS = 1000;
