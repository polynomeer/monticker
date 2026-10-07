import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { TradeHistoryTable } from "@/components/portfolio/TradeHistoryTable";
import { EMOTIONS, emotionLabel } from "@/components/wallet/emotions";
import { originLabel } from "@/components/wallet/origin";
import type { TradeHistory } from "@/hooks/usePaperTrade";

const mockFetch = vi.fn();
global.fetch = mockFetch;

function row(id: number, extra: Partial<TradeHistory>): TradeHistory {
  return {
    id, side: "BUY", stockId: 1, symbol: "005930", name: "삼성전자", quantity: 1, price: 70000, amount: 70000,
    tradedAt: "2026-10-01T01:00:00Z", ...extra,
  };
}

beforeEach(() => mockFetch.mockReset());

describe("TradeHistoryTable", () => {
  // ADR-085 — 감정 태그는 내역 응답에 함께 온다. 거래마다 감정 API를 부르지 않는다(N+1 제거)
  it("감정과 진입 경로를 응답에서 바로 그리고 거래별 조회를 하지 않는다", () => {
    render(
      <TradeHistoryTable history={[
        row(1, { source: "WATCH_RULE", originRef: 7, emotion: "PLANNED" }),
        row(2, { source: "MANUAL", emotion: "IMPATIENT", orderType: "LIMIT" }),
        row(3, { source: null, emotion: null }),
      ]} />,
    );

    expect(screen.getByText("Watch Rule #7")).toBeInTheDocument();
    expect(screen.getByText("계획대로")).toBeInTheDocument();
    expect(screen.getByText("조급함")).toBeInTheDocument();
    expect(screen.getByText("직접 · 지정가")).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });
});

describe("emotion and origin labels", () => {
  it("PLANNED·IMPATIENT가 백엔드 EmotionType과 1:1로 있다", () => {
    expect(EMOTIONS.map((e) => e.value)).toEqual(expect.arrayContaining(["PLANNED", "IMPATIENT"]));
    expect(emotionLabel("PLANNED")).toBe("계획대로");
    expect(emotionLabel("IMPATIENT")).toBe("조급함");
  });

  it("출처를 모르면 라벨을 지어내지 않는다", () => {
    expect(originLabel("CONDITIONAL", 9)).toBe("조건부 #9");
    expect(originLabel("STRATEGY", 2)).toBe("전략 #2");
    expect(originLabel(null)).toBeNull();
    expect(originLabel("SOMETHING")).toBeNull();
  });
});
