import { describe, expect, it } from "vitest";
import { buildFrontierOption, FRONTIER_SERIES, frontierTooltip } from "@/components/stock/chart/frontierOption";
import { appendPeriod, customPeriodError, minusYears, periodLabel } from "@/components/analytics/analysisPeriod";
import type { FrontierChartTheme } from "@/components/stock/chart/types";

const theme: FrontierChartTheme = {
  bg: "#000000", text: "#ffffff", grid: "#333333", sample: "#c3c8e2", frontier: "#bd93f9",
  maxSharpe: "#50fa7b", optimal: "#bd93f9", equalWeight: "#ffb86c", held: "#8be9fd",
};

type Series = { name: string; type: string; data: [number, number, number | null][] };

describe("buildFrontierOption", () => {
  const opt = buildFrontierOption({
    samples: [{ risk: 20, ret: 10, sharpe: 0.5 }, { risk: 15, ret: 6, sharpe: 0.4 }],
    frontier: [{ risk: 30, ret: 18 }, { risk: 12, ret: 5 }],
    maxSharpe: { risk: 18, ret: 12, sharpe: 0.67 },
  }, theme);
  const series = opt.series as Series[];
  const byName = (n: string) => series.find((s) => s.name === n)!;

  it("표본은 산점도, 프론티어는 위험 오름차순 선, 샤프 최대 지점은 별도 계열", () => {
    expect(byName(FRONTIER_SERIES.samples).type).toBe("scatter");
    expect(byName(FRONTIER_SERIES.samples).data).toEqual([[20, 10, 0.5], [15, 6, 0.4]]);
    expect(byName(FRONTIER_SERIES.frontier).type).toBe("line");
    expect(byName(FRONTIER_SERIES.frontier).data.map((d) => d[0])).toEqual([12, 30]);
    expect(byName(FRONTIER_SERIES.maxSharpe).data).toEqual([[18, 12, 0.67]]);
  });

  it("샤프 최대 지점은 과거 데이터 기준이라고 표시하고, 데이터 없는 계열은 범례에서 뺀다", () => {
    expect(FRONTIER_SERIES.maxSharpe).toBe("샤프 비율 최대 지점 (과거 데이터 기준)");
    expect(opt.legend.data).toEqual([FRONTIER_SERIES.samples, FRONTIER_SERIES.frontier, FRONTIER_SERIES.maxSharpe]);
  });

  it("움직임 줄이기면 애니메이션을 끈다", () => {
    expect(buildFrontierOption({ samples: [], frontier: [] }, theme, true).animation).toBe(false);
  });

  it("툴팁은 위험·수익과 샤프를 보인다", () => {
    expect(frontierTooltip("무작위 포트폴리오", [20, 10, 0.5])).toBe("무작위 포트폴리오<br/>예상 위험 20.00%<br/>기대 수익 10.00%<br/>샤프 0.50");
    expect(frontierTooltip("동일가중", [20, 10, null])).not.toContain("샤프");
  });
});

describe("analysisPeriod", () => {
  const today = "2026-10-08";

  it("직접 지정 기간은 60일~3년, 오늘 이전, 순서가 맞아야 한다", () => {
    expect(customPeriodError("2025-10-08", today, today)).toBeNull();
    expect(customPeriodError("2026-08-10", today, today)).toBe("분석 기간은 최소 60일입니다");
    expect(customPeriodError("2026-08-09", today, today)).toBeNull();
    expect(customPeriodError("2023-10-08", today, today)).toBeNull();
    expect(customPeriodError("2023-10-07", today, today)).toBe("분석 기간은 최대 3년입니다");
    expect(customPeriodError("2026-01-01", "2026-10-09", today)).toBe("종료일은 오늘 이후일 수 없습니다");
    expect(customPeriodError(today, "2026-01-01", today)).toBe("시작일은 종료일보다 앞서야 합니다");
    expect(customPeriodError("2026-02-30", today, today)).toBe("시작일과 종료일을 모두 입력하세요");
  });

  it("윤일에서 연 단위로 빼면 2월 말일로 맞춘다", () => {
    expect(minusYears("2028-02-29", 3)).toBe("2025-02-28");
  });

  it("프리셋은 period만, 직접 지정은 from·to를 붙인다", () => {
    const a = new URLSearchParams();
    appendPeriod(a, { kind: "6M" });
    expect(a.toString()).toBe("period=6M");
    const b = new URLSearchParams();
    appendPeriod(b, { kind: "CUSTOM", from: "2025-01-02", to: "2025-12-30" });
    expect(b.toString()).toBe("period=CUSTOM&from=2025-01-02&to=2025-12-30");
    expect(periodLabel({ kind: "1Y" })).toBe("1년");
  });
});
