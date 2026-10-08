// 실전투자 화면의 "내보내기" — 지금 화면에 보이는 보유 종목·주문·정산 표를 CSV로(브라우저 안에서만 만든다).
//
// 넣지 않는 것: API 키·토큰·계좌 원번호. 계좌는 화면과 같은 마스킹(끝 두 자리)만 쓴다. 이 파일의 행 빌더는
// 화면 표가 받는 응답 필드만 읽는다(BrokerageAccountResponse 전체를 받지 않는다 — 실수로 비밀 필드가 섞이지 않게).
// 셀 이스케이프(수식 주입 방지·따옴표)와 BOM은 공용 downloadCsv/toCsv가 한다.
import type { BrokerageHolding, BrokerageOrderResponse, BrokerageSettlementResponse, ConditionalOrderResponse } from "@monticker/types";
import { downloadCsv, kstDateStamp, type CsvCell } from "@/components/portfolio/csv";
import { isUnresolvedOrderStatus } from "@/hooks/useBrokerage";
import { ORDER_STATUS, maskAccount, sideLabel } from "./shared";
import { COND_STATUS, TRIGGER_LABEL } from "./ConditionalOrderRow";
import { SETTLEMENT_STATUS } from "./SettlementsTable";

export interface CsvTable { headers: string[]; rows: CsvCell[][] }

/** ISO 시각 → KST "YYYY-MM-DD HH:mm:ss". 엑셀에서 정렬되는 형태. 브라우저 시간대와 무관. */
export function kstDateTime(iso: string | null | undefined): string {
  if (!iso) return "";
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return "";
  const k = new Date(t + 9 * 60 * 60 * 1000);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${k.getUTCFullYear()}-${p(k.getUTCMonth() + 1)}-${p(k.getUTCDate())} ${p(k.getUTCHours())}:${p(k.getUTCMinutes())}:${p(k.getUTCSeconds())}`;
}

/** 보유 종목 — 화면 표(종목·수량·평균단가·현재가·평가손익·수익률)와 같은 계산. */
export function holdingsTable(holdings: BrokerageHolding[], nameOf: (symbol: string) => string | undefined, accountNumber: string | null | undefined): CsvTable {
  const account = maskAccount(accountNumber);
  return {
    headers: ["계좌", "종목명", "종목코드", "수량", "평균단가", "현재가", "평가손익", "수익률(%)"],
    rows: holdings.map((h) => {
      const pnl = (h.currentPrice - h.avgPrice) * h.quantity;
      const rate = h.avgPrice > 0 ? Number((((h.currentPrice - h.avgPrice) / h.avgPrice) * 100).toFixed(2)) : null;
      return [account, nameOf(h.symbol) ?? h.symbol, h.symbol, h.quantity, h.avgPrice, h.currentPrice, pnl, rate];
    }),
  };
}

function orderPrice(o: BrokerageOrderResponse): CsvCell {
  if (o.avgFillPrice) return o.avgFillPrice;
  if (o.orderType === "LIMIT") return o.limitPrice;
  return "시장가";
}

/** 일반 주문 — /brokerage/orders 주문 내역 표. 결과 불명 주문의 체결 수량은 화면처럼 "?"(0으로 단정하지 않는다). */
export function ordersTable(orders: BrokerageOrderResponse[], accountNumber: string | null | undefined): CsvTable {
  const account = maskAccount(accountNumber);
  return {
    headers: ["계좌", "주문번호", "주문시각(KST)", "종목코드", "구분", "유형", "가격", "수량", "체결수량", "상태", "사유"],
    rows: orders.map((o) => [
      account, o.pgOrderId ?? "", kstDateTime(o.submittedAt), o.symbol, sideLabel(o.side),
      o.orderType === "LIMIT" ? "지정가" : "시장가", orderPrice(o), o.quantity,
      isUnresolvedOrderStatus(o.status) ? "?" : o.filledQty,
      ORDER_STATUS[o.status]?.label ?? o.status, o.rejectReason ?? "",
    ]),
  };
}

export type OrderTimelineEntry =
  | { kind: "REGULAR"; at: string; order: BrokerageOrderResponse }
  | { kind: "CONDITIONAL"; at: string; order: ConditionalOrderResponse };

/** /brokerage 대시보드 "최근 주문" — 일반·조건부를 시간순으로 합친 화면 그대로. */
export function timelineTable(entries: OrderTimelineEntry[], accountNumber: string | null | undefined): CsvTable {
  const account = maskAccount(accountNumber);
  return {
    headers: ["계좌", "시각(KST)", "종류", "종목코드", "구분", "수량", "가격", "상태"],
    rows: entries.map((e) => e.kind === "REGULAR"
      ? [account, kstDateTime(e.at), "일반", e.order.symbol, sideLabel(e.order.side), e.order.quantity, orderPrice(e.order),
         ORDER_STATUS[e.order.status]?.label ?? e.order.status]
      : [account, kstDateTime(e.at), `조건부 · ${TRIGGER_LABEL[e.order.triggerType] ?? e.order.triggerType}`, e.order.symbol,
         sideLabel(e.order.side), e.order.quantity, e.order.triggerPrice, COND_STATUS[e.order.status]?.label ?? e.order.status]),
  };
}

/** 정산(체결) 내역 — SettlementsTable과 같은 열. */
export function settlementsTable(rows: BrokerageSettlementResponse[], accountNumber: string | null | undefined): CsvTable {
  const account = maskAccount(accountNumber);
  return {
    headers: ["계좌", "정산예정일", "종목코드", "구분", "체결수량", "체결가", "수수료", "세금", "정산금액", "상태"],
    rows: rows.map((s) => [
      account, s.settleDate, s.symbol, sideLabel(s.side), s.quantity, s.fillPrice, s.fee, s.tax,
      s.side === "BUY" ? -s.netAmount : s.netAmount, SETTLEMENT_STATUS[s.status]?.label ?? s.status,
    ]),
  };
}

/** `brokerage-{kind}-YYYYMMDD.csv`(KST 날짜)로 내려받는다. */
export function exportBrokerageCsv(kind: "holdings" | "orders" | "settlements", table: CsvTable, now: Date = new Date()) {
  downloadCsv(`brokerage-${kind}-${kstDateStamp(now)}.csv`, table.headers, table.rows);
}
