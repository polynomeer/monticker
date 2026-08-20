import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import ChangeRateBadge from "@/components/screener/ChangeRateBadge";
import { CHART_THEMES } from "@/stores/themeStore";

// 등락률 배지: 상승/하락 부호와 색상은 사용자가 스크리너에서 매수/매도를
// 판단하는 가장 중요한 시각 신호다. 부호가 틀리면 실제 손실로 이어질 수 있다.
describe("ChangeRateBadge", () => {
  it("상승(rate >= 0)이면 +부호와 upColor를 사용한다", () => {
    render(<ChangeRateBadge rate={1.23} amount={5_000_000} />);

    expect(screen.getByText("+1.23%")).toBeInTheDocument();
    expect(screen.getByText("+5,000,000")).toHaveStyle({ color: CHART_THEMES.default.upColor });
  });

  it("rate가 정확히 0이면 상승(+)으로 취급한다", () => {
    render(<ChangeRateBadge rate={0} amount={0} />);

    expect(screen.getByText("+0.00%")).toBeInTheDocument();
  });

  it("하락(rate < 0)이면 부호 없이 음수로 표시하고 downColor를 사용한다", () => {
    render(<ChangeRateBadge rate={-2.5} amount={-3_000_000} />);

    expect(screen.getByText("-2.50%")).toBeInTheDocument();
    expect(screen.getByText("-3,000,000")).toHaveStyle({ color: CHART_THEMES.default.downColor });
  });

  it("거래대금이 1억 이상이면 억 단위로 축약한다", () => {
    render(<ChangeRateBadge rate={0.5} amount={250_000_000} />);

    expect(screen.getByText("+3억")).toBeInTheDocument();
  });

  it("소수 등락률은 소수점 둘째 자리까지 반올림한다", () => {
    render(<ChangeRateBadge rate={1.005} amount={0} />);

    expect(screen.getByText("+1.00%")).toBeInTheDocument();
  });
});
