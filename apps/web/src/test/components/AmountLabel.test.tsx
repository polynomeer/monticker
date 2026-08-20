import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import AmountLabel from "@/components/screener/AmountLabel";

// 거래대금 표시는 사용자가 스크리너 테이블에서 가장 먼저 보는 숫자다.
// 단위 경계(만/억/조)에서 잘못 반올림되거나 잘못된 단위를 붙이면 즉시 눈에 띈다.
describe("AmountLabel", () => {
  it.each([
    [9_999, "9,999"],
    [10_000, "1만"],
    [12_345, "1만"],
    [99_999_999, "10000만"],
    [100_000_000, "1억"],
    [250_000_000, "3억"],
    [999_999_999_999, "10000억"],
    [1_000_000_000_000, "1.0조"],
    [2_500_000_000_000, "2.5조"],
  ])("%i원을 %s로 표시한다", (value, expected) => {
    render(<AmountLabel value={value} />);
    expect(screen.getByText(expected)).toBeInTheDocument();
  });

  it("0원은 그대로 0으로 표시한다", () => {
    render(<AmountLabel value={0} />);
    expect(screen.getByText("0")).toBeInTheDocument();
  });
});
