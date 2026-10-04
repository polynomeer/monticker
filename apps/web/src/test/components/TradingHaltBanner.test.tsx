import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { TradingStatusResponse } from "@monticker/types";

const status: { data: TradingStatusResponse | undefined } = { data: undefined };
vi.mock("@/hooks/useBrokerage", () => ({ useTradingStatus: () => status }));

import { TradingHaltBanner } from "@/components/brokerage/TradingHaltBanner";

describe("TradingHaltBanner (ADR-057)", () => {
  it("스위치가 꺼져 있으면 아무것도 그리지 않는다", () => {
    status.data = { halted: false, scope: null, message: null };
    const { container } = render(<TradingHaltBanner enabled />);
    expect(container).toBeEmptyDOMElement();
  });

  it("켜져 있으면 서버 문구와 화면별 안내를 경고로 보여준다", () => {
    status.data = { halted: true, scope: "GLOBAL", message: "실거래 주문이 일시 중단되었습니다: 증권사 점검" };
    render(<TradingHaltBanner enabled note="걸어둔 조건부 주문은 그대로 유지됩니다." />);
    expect(screen.getByRole("alert")).toHaveTextContent("증권사 점검");
    expect(screen.getByRole("alert")).toHaveTextContent("걸어둔 조건부 주문은 그대로 유지됩니다.");
  });
});
