import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import {
  bpsText, dailyReturnText, deltaPpText, kstToday, kstWeekOf, msText, ratioText, scoreDeltaText,
  type DailyReturn, type ScoreDetails,
} from "@/components/wallet/insights";
import { ScoreDetailGrid } from "@/components/wallet/ScoreDetailGrid";

// ADR-091 — 분모가 0이거나 비교할 수 없으면 "—"(지어낸 숫자 금지), KST 주(월~일)는 브라우저 시간대와 무관
describe("wallet insight formatting", () => {
  it("ratioText는 분모가 0이면 —", () => {
    expect(ratioText({ numerator: 0, denominator: 0, pct: null })).toBe("—");
    expect(ratioText(null)).toBe("—");
    expect(ratioText({ numerator: 2, denominator: 3, pct: 66.67 })).toBe("67%");
  });

  it("지난주 대비는 부호를 붙이고, 비교할 수 없으면 null", () => {
    expect(deltaPpText(12.4)).toBe("+12%p");
    expect(deltaPpText(-5)).toBe("−5%p");
    expect(deltaPpText(0.2)).toBe("±0%p");
    expect(deltaPpText(null)).toBeNull();
    expect(scoreDeltaText(3.46)).toBe("+3.5");
    expect(scoreDeltaText(-2)).toBe("−2");
    expect(scoreDeltaText(null)).toBeNull();
  });

  it("날짜별 수익률은 계산된 날만 숫자", () => {
    const day = (p: Partial<DailyReturn>): DailyReturn => ({
      date: "2026-10-06", startEquity: 1, endEquity: 1, netFlow: 0, pnl: 0, returnPct: 0, status: "OK", ...p,
    });
    expect(dailyReturnText(day({ returnPct: 0.5 }))).toBe("+0.50%");
    expect(dailyReturnText(day({ returnPct: -0.2985 }))).toBe("-0.30%");
    expect(dailyReturnText(day({ returnPct: 0.001 }))).toBe("0.00%");
    expect(dailyReturnText(day({ status: "RESET", returnPct: null }))).toBe("—");
    expect(dailyReturnText(undefined)).toBe("—");
  });

  it("슬리피지·지연 표시", () => {
    expect(bpsText(3.21)).toBe("+3.2bp");
    expect(bpsText(-10.01)).toBe("−10.0bp");
    expect(bpsText(null)).toBe("—");
    expect(msText(4.2)).toBe("4.2ms");
    expect(msText(37.6)).toBe("38ms");
    expect(msText(1300)).toBe("1.3s");
    expect(msText(null)).toBe("—");
  });

  it("KST 주는 월요일에 시작한다", () => {
    expect(kstWeekOf("2026-10-11")).toEqual({ from: "2026-10-05", to: "2026-10-11" }); // 일요일
    expect(kstWeekOf("2026-10-12")).toEqual({ from: "2026-10-12", to: "2026-10-18" }); // 월요일
    expect(kstWeekOf("2026-11-01")).toEqual({ from: "2026-10-26", to: "2026-11-01" }); // 월을 넘는 주
  });

  it("kstToday는 UTC 저녁을 다음 날로 본다", () => {
    expect(kstToday(new Date("2026-10-11T15:00:00Z"))).toBe("2026-10-12"); // KST 월 00:00
    expect(kstToday(new Date("2026-10-11T14:59:59Z"))).toBe("2026-10-11");
  });
});

describe("ScoreDetailGrid", () => {
  const empty = { numerator: 0, denominator: 0, pct: null };
  const details = (p: Partial<ScoreDetails> = {}): ScoreDetails => ({
    weekStart: "2026-10-12",
    lastWeekStart: "2026-10-05",
    planAdherence: { thisWeek: { numerator: 1, denominator: 2, pct: 50 }, lastWeek: { numerator: 1, denominator: 1, pct: 100 }, deltaPp: -50 },
    stopLossAdherence: { thisWeek: empty, lastWeek: empty, deltaPp: null },
    behaviorScore: { thisWeekAvg: 90, lastWeekAvg: 75, thisWeekDays: 1, lastWeekDays: 2, delta: 15 },
    ...p,
  });

  it("값이 있는 지표는 숫자와 지난주 대비, 분모가 0인 지표는 —", () => {
    render(<ScoreDetailGrid details={details()} />);
    expect(screen.getByText("50%")).toBeInTheDocument();
    expect(screen.getByText("지난주 대비 −50%p")).toBeInTheDocument();
    expect(screen.getByText("—")).toBeInTheDocument(); // 손절 준수율
    expect(screen.getByText("이번 주 손절을 정한 손실 매도 없음")).toBeInTheDocument();
    expect(screen.getByText("+15")).toBeInTheDocument();
  });

  it("지난주 점수가 없으면 대비는 —", () => {
    render(<ScoreDetailGrid details={details({ behaviorScore: { thisWeekAvg: 90, lastWeekAvg: null, thisWeekDays: 1, lastWeekDays: 0, delta: null } })} />);
    expect(screen.getByText("지난주 기록 없음")).toBeInTheDocument();
  });

  it("예전 서버 응답(세부 지표 없음)이면 아무것도 그리지 않는다", () => {
    const { container } = render(<ScoreDetailGrid details={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });
});
