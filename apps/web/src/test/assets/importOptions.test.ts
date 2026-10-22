import { describe, expect, it } from "vitest";
import { customFormProblem, parseTypeValues, toImportOptions, type ImportForm } from "@/components/assets/importOptions";
import { portfolioConsentNeeded } from "@/components/assets/PortfolioConsent";

const base: ImportForm = { mode: "CUSTOM", columns: {}, typeValuesText: "", defaultCurrency: "" };

describe("가져오기 설정", () => {
  it("거래 구분 값은 한글 이름이나 영문 이름을 받고, 읽을 수 없는 줄은 줄 번호로 알린다", () => {
    const r = parseTypeValues("주식매수=매수\n\n해외매도 = SELL\n이상한줄\n=매수\n배당=환전");
    expect(r.values).toEqual({ 주식매수: "BUY", 해외매도: "SELL" });
    expect(r.errors).toEqual(['4번째 줄: "이상한줄"', '5번째 줄: "=매수"', '6번째 줄: "배당=환전"']);
  });

  it("표준 템플릿은 형식과 기본 통화만 보낸다", () => {
    expect(toImportOptions({ ...base, mode: "TEMPLATE", columns: { DATE: "x" }, defaultCurrency: "usd" }))
      .toEqual({ format: "MONTICKER_V1", defaultCurrency: "USD" });
  });

  it("열 직접 지정은 비운 열을 빼고 앞뒤 공백을 지워 보낸다", () => {
    const o = toImportOptions({ ...base, columns: { DATE: " 거래일자 ", TYPE: "구분", NAME: "  " }, typeValuesText: "주식매수=매수" });
    expect(o).toEqual({ format: "CUSTOM", columns: { DATE: "거래일자", TYPE: "구분" }, typeValues: { 주식매수: "BUY" }, defaultCurrency: undefined });
  });

  it("필수 열이 비었거나 거래 구분 값을 못 읽으면 보내기 전에 막는다", () => {
    expect(customFormProblem({ ...base, columns: { DATE: "일자" } })).toContain("거래 구분");
    expect(customFormProblem({ ...base, columns: { DATE: "일자", TYPE: "구분" }, typeValuesText: "x" })).toContain("1번째 줄");
    expect(customFormProblem({ ...base, columns: { DATE: "일자", TYPE: "구분" } })).toBeNull();
    expect(customFormProblem({ ...base, mode: "TEMPLATE" })).toBeNull();
  });
});

describe("계좌 정보 수집·이용 동의가 필요한가", () => {
  const state = (agreed: boolean, documentVersion: string | null) => ({
    states: [{ type: "PORTFOLIO_DATA_COLLECTION" as const, agreed, documentVersion, currentVersion: "v2", recordedAt: null }],
    missingRequired: [],
  });
  it("처음·철회·문서 변경이면 필요하고, 문서 변경만 '다시 동의'다", () => {
    expect(portfolioConsentNeeded(undefined)).toEqual({ needed: true, renewal: false });
    expect(portfolioConsentNeeded(state(false, null))).toEqual({ needed: true, renewal: false });
    expect(portfolioConsentNeeded(state(false, "v2"))).toEqual({ needed: true, renewal: false });
    expect(portfolioConsentNeeded(state(true, "v1"))).toEqual({ needed: true, renewal: true });
    expect(portfolioConsentNeeded(state(true, "v2"))).toEqual({ needed: false, renewal: false });
  });
});
