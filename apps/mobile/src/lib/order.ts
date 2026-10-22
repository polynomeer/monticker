/** 주문 시트 계산 — 순수 함수라 노드 테스트로 확인한다 */

export type Side = "BUY" | "SELL";

/** 매수는 가용 현금 ÷ 단가(내림), 매도는 보유 수량. 가격을 모르면 0 */
export function maxQuantity(side: Side, cash: number, unitPrice: number, holding: number): number {
  if (side === "SELL") return Math.max(0, Math.floor(holding));
  if (!(unitPrice > 0) || !(cash > 0)) return 0;
  return Math.floor(cash / unitPrice);
}

/** 빠른 수량(10·25·50·100%) — 최대가 있으면 최소 1주 */
export function quickQuantity(max: number, pct: number): number {
  if (max <= 0) return 0;
  return Math.max(1, Math.floor((max * pct) / 100));
}

/** 수량 입력을 0~max 정수로 */
export function clampQuantity(raw: number, max: number): number {
  if (!Number.isFinite(raw)) return 0;
  return Math.min(Math.max(0, Math.floor(raw)), Math.max(0, max));
}

/** "71,000" 같은 입력 → 숫자. 숫자가 없으면 0 */
export function parseAmount(text: string): number {
  const n = Number(text.replace(/[^\d.]/g, ""));
  return Number.isFinite(n) ? n : 0;
}

/** 서버(RiskPreviewRequest·주문 요청)와 같은 기본 검증 — 통과해야 미리보기·제출을 한다 */
export function isOrderable(quantity: number, unitPrice: number, max: number): boolean {
  return Number.isInteger(quantity) && quantity > 0 && quantity <= 1_000_000 && quantity <= max && unitPrice > 0;
}

/** 중복 제출 방지 키(X-Idempotency-Key). 같은 시도 안에서만 같고, 다음 주문은 새 키다 */
export function newIdempotencyKey(now = Date.now(), rand = Math.random): string {
  return `m-${now.toString(36)}-${Math.floor(rand() * 1e12).toString(36)}`;
}
