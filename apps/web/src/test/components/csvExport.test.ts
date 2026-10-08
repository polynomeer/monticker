import { describe, it, expect } from "vitest";
import { csvCell, kstDateStamp, toCsv } from "@/components/portfolio/csv";
import { holdingsTable, kstDateTime, ordersTable, settlementsTable, timelineTable } from "@/components/brokerage/exportCsv";
import type { BrokerageOrderResponse, BrokerageSettlementResponse, ConditionalOrderResponse } from "@monticker/types";

describe("csvCell — 수식 주입 방지", () => {
  it.each([
    ["=HYPERLINK(\"http://evil\",\"x\")", "\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\""],
    ["+cmd|' /C calc'!A0", "'+cmd|' /C calc'!A0"],
    ["-2+3+cmd|' /C calc'!A0", "'-2+3+cmd|' /C calc'!A0"],
    ["@SUM(A1:A2)", "'@SUM(A1:A2)"],
    ["\t=1+1", "'\t=1+1"],
    ["\r=1+1", "\"'\r=1+1\""],
  ])("%j 는 작은따옴표로 텍스트가 된다", (input, expected) => {
    expect(csvCell(input)).toBe(expected);
  });

  it("부호 붙은 순수 숫자 문자열과 숫자 타입은 값으로 둔다", () => {
    expect(csvCell("-3.20")).toBe("-3.20");
    expect(csvCell("+1.5%")).toBe("+1.5%");
    expect(csvCell(-1200)).toBe("-1200");
    expect(csvCell(Number.NaN)).toBe("");
    expect(csvCell(null)).toBe("");
    expect(csvCell(undefined)).toBe("");
  });

  it("쉼표·따옴표·줄바꿈은 따옴표로 감싸고 따옴표는 두 번 쓴다", () => {
    expect(csvCell("a,b")).toBe("\"a,b\"");
    expect(csvCell("say \"hi\"")).toBe("\"say \"\"hi\"\"\"");
    expect(csvCell("line1\nline2")).toBe("\"line1\nline2\"");
  });

  it("toCsv는 CRLF로 행을 잇는다", () => {
    expect(toCsv(["a", "b"], [[1, "=x"]])).toBe("a,b\r\n1,'=x");
  });
});

describe("KST 날짜", () => {
  it("파일 이름 날짜는 브라우저 시간대와 무관하게 KST", () => {
    // 2026-10-08 15:30 UTC = 2026-10-09 00:30 KST
    expect(kstDateStamp(new Date("2026-10-08T15:30:00Z"))).toBe("20261009");
    expect(kstDateStamp(new Date("2026-10-08T14:59:59Z"))).toBe("20261008");
  });

  it("시각 열은 KST YYYY-MM-DD HH:mm:ss, 잘못된 값은 빈칸", () => {
    expect(kstDateTime("2026-10-08T00:01:02Z")).toBe("2026-10-08 09:01:02");
    expect(kstDateTime(null)).toBe("");
    expect(kstDateTime("not-a-date")).toBe("");
  });
});

const order = (o: Partial<BrokerageOrderResponse>): BrokerageOrderResponse => ({
  id: 1, symbol: "005930", side: "BUY", orderType: "LIMIT", quantity: 10, limitPrice: 70000, filledQty: 0, avgFillPrice: null,
  pgOrderId: "KIS-1", status: "SUBMITTED", rejectReason: null, submittedAt: "2026-10-08T01:00:00Z", filledAt: null, needsReview: false,
  ...o,
});

describe("실전투자 CSV 행", () => {
  it("보유 종목: 계좌번호는 끝 두 자리만, 손익·수익률은 화면과 같은 계산", () => {
    const t = holdingsTable([{ symbol: "005930", quantity: 10, avgPrice: 70000, currentPrice: 77000 }], () => "삼성전자", "12345678-01");
    expect(t.rows[0]).toEqual(["••••-••01", "삼성전자", "005930", 10, 70000, 77000, 70000, 10]);
    expect(toCsv(t.headers, t.rows)).not.toContain("12345678");
  });

  it("종목명이 수식이어도 셀은 텍스트가 된다", () => {
    const t = holdingsTable([{ symbol: "X", quantity: 1, avgPrice: 0, currentPrice: 1 }], () => "=1+1", null);
    const csv = toCsv(t.headers, t.rows);
    expect(csv).toContain(",'=1+1,");
    expect(t.rows[0][0]).toBe("—");
    expect(t.rows[0][7]).toBeNull(); // 평균단가 0이면 수익률 없음
  });

  it("주문: 결과 불명 체결 수량은 ?, 거부 사유의 수식도 막는다, 원 계좌번호·키 없음", () => {
    const t = ordersTable([
      order({ status: "UNKNOWN" }),
      order({ id: 2, status: "REJECTED", rejectReason: "=cmd", orderType: "MARKET", limitPrice: null }),
    ], "99998888-77");
    expect(t.rows[0][8]).toBe("?");
    expect(t.rows[0][9]).toBe("결과 확인 중");
    expect(t.rows[1][6]).toBe("시장가");
    const csv = toCsv(t.headers, t.rows);
    expect(csv).toContain("'=cmd");
    expect(csv).not.toContain("99998888");
    expect(csv.toLowerCase()).not.toMatch(/appkey|appsecret|token|secret/);
  });

  it("최근 주문 타임라인: 일반·조건부를 같은 열로", () => {
    const cond = {
      id: 3, symbol: "000660", side: "SELL", triggerType: "STOP_LOSS", triggerPrice: 150000, orderType: "MARKET", limitPrice: null,
      quantity: 5, ocoGroupId: null, status: "ACTIVE", failReason: null, executedOrderId: null,
      createdAt: "2026-10-08T02:00:00Z", triggeredAt: null, expiresAt: null,
    } as ConditionalOrderResponse;
    const t = timelineTable([
      { kind: "CONDITIONAL", at: cond.createdAt, order: cond },
      { kind: "REGULAR", at: "2026-10-08T01:00:00Z", order: order({}) },
    ], "1234");
    expect(t.rows[0]).toEqual(["••••-••34", "2026-10-08 11:00:00", "조건부 · 손절", "000660", "매도", 5, 150000, "감시 중"]);
    expect(t.rows[1][2]).toBe("일반");
    expect(t.rows[1][7]).toBe("접수됨");
  });

  it("정산: 매수는 음수 정산 금액", () => {
    const s: BrokerageSettlementResponse = {
      id: 1, symbol: "005930", side: "BUY", quantity: 1, fillPrice: 70000, grossAmount: 70000, fee: 10, tax: 0, netAmount: 70010,
      settleDate: "2026-10-12", status: "PENDING", settledAt: null,
    };
    expect(settlementsTable([s], "12").rows[0]).toEqual(["••••-••12", "2026-10-12", "005930", "매수", 1, 70000, 10, 0, -70010, "대기 중"]);
  });
});
