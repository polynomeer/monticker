import type { PortfolioAccountKind, PortfolioAccountSource, PortfolioInstitution, PortfolioTransactionType } from "@monticker/types";

export const INSTITUTION_LABEL: Record<PortfolioInstitution, string> = {
  KIS: "한국투자증권", TOSS: "토스증권", KIWOOM: "키움증권", MIRAE: "미래에셋증권", SAMSUNG: "삼성증권", NH: "NH투자증권",
  KB: "KB증권", SHINHAN: "신한투자증권", HANA: "하나증권", DAISHIN: "대신증권", LS: "LS증권", MERITZ: "메리츠증권",
  KAKAOPAY: "카카오페이증권", IBKR: "Interactive Brokers", OTHER: "기타",
};

export const ACCOUNT_KIND_LABEL: Record<PortfolioAccountKind, string> = {
  GENERAL: "일반", ISA: "ISA", PENSION: "연금저축", IRP: "IRP", OTHER: "기타",
};

export const SOURCE_LABEL: Record<PortfolioAccountSource, string> = {
  MANUAL: "손 입력", CSV: "파일 업로드", BROKER_API: "증권사 연동", MYDATA: "마이데이터",
};

export const TX_TYPE_LABEL: Record<PortfolioTransactionType, string> = {
  BUY: "매수", SELL: "매도", DIVIDEND: "배당", DEPOSIT: "입금", WITHDRAWAL: "출금", FEE: "수수료", TAX: "세금",
};

/** 금액 표시 — 원화는 정수, 그 밖의 통화는 소수 2자리 */
export function fmtMoney(v: number | null | undefined, currency: string) {
  if (v == null || Number.isNaN(v)) return "—";
  const digits = currency === "KRW" ? 0 : 2;
  const s = v.toLocaleString("ko-KR", { minimumFractionDigits: digits, maximumFractionDigits: digits });
  return currency === "KRW" ? `${s}원` : `${s} ${currency}`;
}

/** 수량 — 해외 소수점 주식은 소수 6자리까지, 정수면 소수점 없이 */
export function fmtQty(v: number | null | undefined) {
  if (v == null || Number.isNaN(v)) return "—";
  return v.toLocaleString("ko-KR", { maximumFractionDigits: 6 });
}
