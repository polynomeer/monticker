import { describe, expect, it } from "vitest";
import {
  candleIndexAt, fmtCandleLabel, fractionalIndexAt, inferInterval, timeAtIndex, toChartInterval,
} from "@/components/stock/chart/chartTime";
import {
  decimate, formatMeasure, heikinAshi, isDrawingMeaningful, magnetSnap, measure, shiftPoints, zoomWindow,
} from "@/components/stock/chart/drawingGeometry";
import { kstDateToEpoch } from "@/components/stock/chart/tradeMarkers";

/** "YYYY-MM-DD HH:mm"(KST) → epoch seconds */
const kst = (s: string) => Math.floor(Date.parse(`${s.replace(" ", "T")}:00+09:00`) / 1000);

// 2026-10-05(월) 09:00~09:04 KST 1분봉 5개
const minuteBars = [0, 1, 2, 3, 4].map((i) => kst("2026-10-05 09:00") + i * 60);
// 일봉: 10-02(금), 10-05(월), 10-06(화) KST 자정
const dailyBars = ["2026-10-02", "2026-10-05", "2026-10-06"].map(kstDateToEpoch);

describe("chartTime — Asia/Seoul 버킷 위치", () => {
  it("분봉에서 이벤트는 그날 첫 봉이 아니라 자기 시각의 봉에 붙는다", () => {
    expect(candleIndexAt(minuteBars, kst("2026-10-05 09:03") + 25, "1m")).toBe(3);
    expect(candleIndexAt(minuteBars, kst("2026-10-05 09:00"), "1m")).toBe(0);
  });

  it("일봉에서는 KST 날짜 경계로 묶는다 — UTC로는 전날인 KST 새벽 시각도 그날 봉", () => {
    // 2026-10-05 08:00 KST = 2026-10-04 23:00 UTC
    expect(candleIndexAt(dailyBars, kst("2026-10-05 08:00"), "1d")).toBe(1);
    expect(candleIndexAt(dailyBars, kst("2026-10-06 23:59"), "1d")).toBe(2);
  });

  it("봉 없는 날(주말)은 직전 봉, 구간 밖은 -1", () => {
    expect(candleIndexAt(dailyBars, kst("2026-10-03 12:00"), "1d")).toBe(0);
    expect(candleIndexAt(dailyBars, kst("2026-10-01 12:00"), "1d")).toBe(-1);
    expect(candleIndexAt(dailyBars, kst("2026-10-07 00:00"), "1d")).toBe(-1);
    expect(candleIndexAt([], 0, "1d")).toBe(-1);
  });

  it("소수 인덱스 ↔ 시각 왕복", () => {
    for (const idx of [0, 1.5, 2.25, 3]) {
      expect(fractionalIndexAt(minuteBars, timeAtIndex(minuteBars, idx, "1m"), "1m")).toBeCloseTo(idx, 2);
    }
    // 봉 시각 그대로면 정수 인덱스
    expect(fractionalIndexAt(dailyBars, dailyBars[1], "1d")).toBe(1);
  });

  it("구간 밖 시각은 평균 봉 간격으로 외삽한다", () => {
    expect(fractionalIndexAt(minuteBars, minuteBars[4] + 120, "1m")).toBeCloseTo(6);
    expect(fractionalIndexAt(minuteBars, minuteBars[0] - 120, "1m")).toBeCloseTo(-2);
    expect(timeAtIndex(minuteBars, -1, "1m")).toBe(minuteBars[0] - 60);
    expect(timeAtIndex(minuteBars, 6, "1m")).toBe(minuteBars[4] + 120);
  });

  it("1분봉에서 그린 점을 일봉에서 보면 그날 봉 안에 놓인다", () => {
    const t = kst("2026-10-05 10:00");
    const fi = fractionalIndexAt(dailyBars, t, "1d");
    expect(Math.floor(fi)).toBe(1);
    expect(fi - 1).toBeCloseTo(10 / 24, 3);
  });

  it("라벨은 프로세스 시간대와 무관하게 KST", () => {
    expect(fmtCandleLabel(kstDateToEpoch("2026-10-05"), "1d")).toBe("2026-10-05");
    expect(fmtCandleLabel(kst("2026-10-05 09:03"), "1m")).toBe("10-05 09:03");
  });

  it("간격 문자열 정규화·추정", () => {
    expect(toChartInterval("15m")).toBe("15m");
    expect(toChartInterval("1w")).toBe("1d");
    expect(toChartInterval(null)).toBe("1d");
    expect(inferInterval(minuteBars)).toBe("1m");
    expect(inferInterval(dailyBars)).toBe("1d");
    expect(inferInterval([0, 180, 360])).toBe("3m");
  });
});

describe("drawingGeometry", () => {
  it("측정 — 가격 차이, 변화율, 봉 수", () => {
    const r = measure({ index: 2, price: 50_000 }, { index: 14, price: 51_500 });
    expect(r).toEqual({ priceDiff: 1500, pct: 3, bars: 12 });
    expect(formatMeasure(r)).toBe("+1,500 (+3.00%) · 12봉");
    const down = measure({ index: 10, price: 200 }, { index: 7, price: 150 });
    expect(down.bars).toBe(3);
    expect(formatMeasure(down)).toBe("−50 (−25.00%) · 3봉");
    expect(measure({ index: 0, price: 0 }, { index: 1, price: 5 }).pct).toBeNull();
  });

  it("자석 — 가장 가까운 OHLC 값", () => {
    const c = { open: 100, high: 120, low: 90, close: 110 };
    expect(magnetSnap(c, 118)).toBe(120);
    expect(magnetSnap(c, 93)).toBe(90);
    expect(magnetSnap(c, 106)).toBe(110);
    expect(magnetSnap(c, 101)).toBe(100);
    expect(magnetSnap(undefined, 77)).toBe(77);
  });

  it("확대 구간 — 정렬·자르기·최소 폭", () => {
    expect(zoomWindow(8.4, 2.6, 20)).toEqual({ startValue: 3, endValue: 8 });
    expect(zoomWindow(-5, 50, 20)).toEqual({ startValue: 0, endValue: 19 });
    expect(zoomWindow(19, 19, 20, 5)).toEqual({ startValue: 15, endValue: 19 });
    expect(zoomWindow(3, 3, 1)).toEqual({ startValue: 0, endValue: 0 });
    expect(zoomWindow(0, 1, 0)).toBeNull();
  });

  it("간격을 바꿨을 때 한 봉으로 뭉개지는 선은 그리지 않는다", () => {
    const short = { tool: "TREND_LINE" as const, points: [{ time: minuteBars[0], price: 1 }, { time: minuteBars[3], price: 2 }] };
    expect(isDrawingMeaningful(short, "1m")).toBe(true);
    expect(isDrawingMeaningful(short, "1d")).toBe(false);
    const multiDay = { tool: "PEN" as const, points: [{ time: dailyBars[0], price: 1 }, { time: dailyBars[2], price: 2 }] };
    expect(isDrawingMeaningful(multiDay, "1d")).toBe(true);
    expect(isDrawingMeaningful({ tool: "HORIZONTAL_LINE", points: [{ time: 0, price: 1 }] }, "1d")).toBe(true);
    expect(isDrawingMeaningful({ tool: "TEXT", points: [{ time: 0, price: 1 }] }, "1h")).toBe(true);
  });

  it("하이킨아시", () => {
    const ha = heikinAshi([
      { time: 1, open: 10, high: 14, low: 8, close: 12 },
      { time: 2, open: 12, high: 16, low: 11, close: 15 },
    ]);
    expect(ha[0]).toMatchObject({ time: 1, open: 11, close: 11, high: 14, low: 8 });
    expect(ha[1]).toMatchObject({ time: 2, open: 11, close: 13.5, high: 16, low: 11 });
  });

  it("솎기는 끝점을 지키고 상한을 넘지 않는다", () => {
    const pts = Array.from({ length: 1000 }, (_, i) => i);
    const out = decimate(pts, 300);
    expect(out).toHaveLength(300);
    expect(out[0]).toBe(0);
    expect(out[299]).toBe(999);
    expect(decimate([1, 2, 3], 10)).toEqual([1, 2, 3]);
  });

  it("옮기기 — 봉 인덱스·가격 이동", () => {
    const toIndex = (t: number) => fractionalIndexAt(dailyBars, t, "1d");
    const toTime = (i: number) => timeAtIndex(dailyBars, i, "1d");
    const moved = shiftPoints([{ time: dailyBars[0], price: 100 }], toIndex, toTime, 2, (p) => p + 5);
    expect(moved).toEqual([{ time: dailyBars[2], price: 105 }]);
  });
});
