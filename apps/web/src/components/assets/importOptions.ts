import type { CsvField, ImportOptions, PortfolioTransactionType } from "@monticker/types";
import { TX_TYPE_LABEL } from "./labels";

/** 열 직접 지정 — 화면에 보이는 순서. 날짜·구분은 서버도 필수로 본다 */
export const CSV_FIELDS: { field: CsvField; label: string; required?: boolean; placeholder: string }[] = [
  { field: "DATE", label: "거래일", required: true, placeholder: "예: 거래일자" },
  { field: "TYPE", label: "거래 구분", required: true, placeholder: "예: 거래구분" },
  { field: "SYMBOL", label: "종목 코드", placeholder: "예: 종목코드" },
  { field: "NAME", label: "종목명", placeholder: "예: 종목명" },
  { field: "QUANTITY", label: "수량", placeholder: "예: 체결수량" },
  { field: "PRICE", label: "단가", placeholder: "예: 체결단가" },
  { field: "AMOUNT", label: "금액", placeholder: "예: 거래금액" },
  { field: "FEE", label: "수수료", placeholder: "예: 수수료" },
  { field: "TAX", label: "세금", placeholder: "예: 제세금" },
  { field: "CURRENCY", label: "통화", placeholder: "예: 통화" },
  { field: "MEMO", label: "메모", placeholder: "예: 적요" },
];

const TYPE_BY_WORD: Record<string, PortfolioTransactionType> = Object.fromEntries([
  ...Object.keys(TX_TYPE_LABEL).map((k) => [k, k]),
  ...Object.entries(TX_TYPE_LABEL).map(([k, v]) => [v, k]),
]) as Record<string, PortfolioTransactionType>;

/**
 * "주식매수=매수" 줄들 → 거래 구분 값 매핑. 오른쪽은 매수·매도·배당·입금·출금·수수료·세금(또는 BUY 같은 이름).
 * 읽을 수 없는 줄은 errors에 줄 번호와 함께 담는다. 흔한 값(매수·현금매수·배당금 등)은 서버가 이미 안다.
 */
export function parseTypeValues(text: string): { values: Record<string, PortfolioTransactionType>; errors: string[] } {
  const values: Record<string, PortfolioTransactionType> = {};
  const errors: string[] = [];
  text.split(/\r?\n/).forEach((raw, i) => {
    const line = raw.trim();
    if (!line) return;
    const at = line.lastIndexOf("=");
    const key = at > 0 ? line.slice(0, at).trim() : "";
    const type = at > 0 ? TYPE_BY_WORD[line.slice(at + 1).trim().toUpperCase()] ?? TYPE_BY_WORD[line.slice(at + 1).trim()] : undefined;
    if (!key || !type) errors.push(`${i + 1}번째 줄: "${line}"`);
    else values[key] = type;
  });
  return { values, errors };
}

export interface ImportForm {
  mode: "TEMPLATE" | "CUSTOM";
  columns: Partial<Record<CsvField, string>>;
  typeValuesText: string;
  defaultCurrency: string;
}

export function toImportOptions(f: ImportForm): ImportOptions {
  const defaultCurrency = f.defaultCurrency.trim().toUpperCase() || undefined;
  if (f.mode === "TEMPLATE") return { format: "MONTICKER_V1", defaultCurrency };
  const columns = Object.fromEntries(Object.entries(f.columns).map(([k, v]) => [k, v?.trim()]).filter(([, v]) => v)) as Partial<Record<CsvField, string>>;
  const { values } = parseTypeValues(f.typeValuesText);
  return { format: "CUSTOM", columns, typeValues: Object.keys(values).length ? values : undefined, defaultCurrency };
}

/** 열 직접 지정에서 아직 못 보내는 이유 — 없으면 null */
export function customFormProblem(f: ImportForm): string | null {
  if (f.mode !== "CUSTOM") return null;
  const missing = CSV_FIELDS.filter((c) => c.required && !f.columns[c.field]?.trim()).map((c) => c.label);
  if (missing.length) return `필수 열 이름을 적어 주세요: ${missing.join(", ")}`;
  const { errors } = parseTypeValues(f.typeValuesText);
  if (errors.length) return `거래 구분 값을 읽지 못했습니다 — ${errors.join(", ")}`;
  return null;
}
