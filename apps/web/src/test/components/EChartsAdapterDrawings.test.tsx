import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import EChartsAdapter from "@/components/stock/chart/EChartsAdapter";
import type { CandleData, Drawing, EventMarker } from "@/components/stock/chart/types";

// echarts 목: x 픽셀 = 48 + 인덱스×10, y 픽셀 = 500 − 가격. zr 핸들러를 잡아 두고 직접 부른다.
const setOption = vi.fn();
const dispatchAction = vi.fn();
const zrHandlers: Record<string, (e: unknown) => void> = {};
vi.mock("echarts", () => ({
  init: () => ({
    setOption,
    dispatchAction,
    on: vi.fn(),
    getZr: () => ({ on: (name: string, fn: (e: unknown) => void) => { zrHandlers[name] = fn; } }),
    isDisposed: () => false,
    dispose: vi.fn(),
    resize: vi.fn(),
    getWidth: () => 800,
    getOption: () => ({ dataZoom: [{ start: 0, end: 100 }] }),
    containPixel: () => true,
    convertToPixel: (finder: Record<string, number>, v: number) => ("xAxisIndex" in finder ? 48 + v * 10 : 500 - v),
    convertFromPixel: (_finder: Record<string, number>, v: number) => 500 - v,
  }),
}));

const theme = { bg: "#000000", text: "#ffffff", grid: "#333333", upColor: "#2563eb", downColor: "#f97316", accent: "#bd93f9" };
const kst = (s: string) => Math.floor(Date.parse(`${s.replace(" ", "T")}:00+09:00`) / 1000);
// 분봉 3일치처럼 같은 날짜가 반복되는 구간: 10-05 09:00~09:04, 10-06 09:00~09:04
const minuteCandles: CandleData[] = [
  ...[0, 1, 2, 3, 4].map((i) => kst("2026-10-05 09:00") + i * 60),
  ...[0, 1, 2, 3, 4].map((i) => kst("2026-10-06 09:00") + i * 60),
].map((time, i) => ({ time, open: 100 + i, high: 110 + i, low: 90 + i, close: 105 + i, volume: 1 }));

type Opt = {
  series?: Array<{ name: string; type: string; data: unknown[]; areaStyle?: unknown; markPoint?: { data: Array<{ coord: [number, number]; eventId?: number }> } }>;
  xAxis?: Array<{ data: string[] }>;
  graphic?: { elements: Array<{ id: string; $action?: string; children: Array<Record<string, unknown>> }> };
  dataZoom?: Array<Record<string, unknown>>;
};
const fullOption = () => setOption.mock.calls.map((c) => c[0] as Opt).filter((o) => Array.isArray(o.series)).at(-1)!;
const lastGraphic = () => setOption.mock.calls.map((c) => c[0] as Opt).filter((o) => o.graphic).at(-1)!.graphic!.elements[0];
/** 인덱스·가격 → 목 픽셀 이벤트 */
const at = (index: number, price: number) => ({ offsetX: 48 + index * 10, offsetY: 500 - price });

beforeEach(() => {
  setOption.mockClear();
  dispatchAction.mockClear();
  for (const k of Object.keys(zrHandlers)) delete zrHandlers[k];
});

describe("EChartsAdapter — 차트 유형", () => {
  it("라인·영역은 종가 line 시리즈, 하이킨아시는 평균 봉 candlestick", async () => {
    const { rerender } = render(<EChartsAdapter candles={minuteCandles} theme={theme} interval="1m" chartType="line" />);
    await waitFor(() => expect(fullOption()).toBeTruthy());
    let main = fullOption().series!.find((s) => s.name === "OHLC")!;
    expect(main.type).toBe("line");
    expect(main.data).toEqual(minuteCandles.map((c) => c.close));
    expect(main.areaStyle).toBeUndefined();

    rerender(<EChartsAdapter candles={minuteCandles} theme={theme} interval="1m" chartType="area" />);
    await waitFor(() => expect(fullOption().series!.find((s) => s.name === "OHLC")!.areaStyle).toBeDefined());

    rerender(<EChartsAdapter candles={minuteCandles} theme={theme} interval="1m" chartType="heikin-ashi" />);
    await waitFor(() => expect(fullOption().series!.find((s) => s.name === "OHLC")!.type).toBe("candlestick"));
    main = fullOption().series!.find((s) => s.name === "OHLC")!;
    // 첫 HA 봉: open=(100+105)/2, close=(100+110+90+105)/4
    expect(main.data[0]).toEqual([102.5, 101.25, 90, 110]);
  });
});

describe("EChartsAdapter — 마커 위치(버킷)", () => {
  it("분봉에서 이벤트는 그날 첫 봉이 아니라 자기 봉 인덱스에 붙고, 라벨은 KST 시각", async () => {
    const events: EventMarker[] = [
      { id: 1, time: kst("2026-10-06 09:03") + 20, eventType: "NEWS_PUBLISHED", title: "뉴스", importanceScore: 50 },
      { id: 2, time: kst("2026-10-07 10:00"), eventType: "NEWS_PUBLISHED", title: "구간 밖", importanceScore: 50 },
    ];
    render(<EChartsAdapter candles={minuteCandles} theme={theme} interval="1m" events={events} />);
    await waitFor(() => expect(fullOption()).toBeTruthy());
    const marks = fullOption().series!.find((s) => s.name === "OHLC")!.markPoint!.data.filter((d) => d.eventId != null);
    expect(marks.map((m) => [m.eventId, m.coord[0]])).toEqual([[1, 8]]);
    expect(fullOption().xAxis![0].data[8]).toBe("10-06 09:03");
  });
});

describe("EChartsAdapter — 드로잉", () => {
  it("저장된 드로잉을 시각 버킷 인덱스로 그리고, 그룹을 통째로 교체한다", async () => {
    const drawings: Drawing[] = [
      { id: "tl", tool: "TREND_LINE", points: [{ time: minuteCandles[6].time, price: 100 }, { time: minuteCandles[8].time, price: 120 }] },
      { id: "hl", tool: "HORIZONTAL_LINE", points: [{ time: 0, price: 150 }] },
      { id: "tx", tool: "TEXT", points: [{ time: minuteCandles[2].time, price: 50 }], text: "메모" },
    ];
    render(<EChartsAdapter candles={minuteCandles} theme={theme} interval="1m" drawings={drawings} />);
    await waitFor(() => expect(lastGraphic()).toBeTruthy());
    const g = lastGraphic();
    // graphic 컴포넌트를 통째로 갈아 끼운다 — 그룹 $action: "replace"는 ECharts 6에서 글자가 바뀐 text를 화면에서 빠뜨린다
    expect(g.$action).toBeUndefined();
    const graphicCall = setOption.mock.calls.filter((c) => (c[0] as Opt).graphic).at(-1)!;
    expect(graphicCall[1]).toEqual({ replaceMerge: ["graphic"] });
    const byId = Object.fromEntries(g.children.map((c) => [c.id, c]));
    expect(byId.tl.shape).toEqual({ x1: 108, y1: 400, x2: 128, y2: 380 });
    expect(byId.hl.shape).toEqual({ x1: 48, y1: 350, x2: 800 - 82, y2: 350 });
    expect(byId.tx.style).toMatchObject({ text: "메모", x: 68, y: 450 });
    expect(byId.tl.draggable).toBe(true);
  });

  it("일봉에서 보면 한 봉에 뭉개지는 분봉 추세선은 그리지 않는다", async () => {
    const daily: CandleData[] = ["2026-10-05", "2026-10-06"].map((d, i) => ({
      time: Math.floor(Date.parse(`${d}T00:00:00+09:00`) / 1000), open: 1, high: 2, low: 0.5, close: 1.5 + i,
    }));
    const drawings: Drawing[] = [
      { id: "short", tool: "TREND_LINE", points: [{ time: minuteCandles[0].time, price: 1 }, { time: minuteCandles[3].time, price: 2 }] },
      { id: "long", tool: "TREND_LINE", points: [{ time: minuteCandles[0].time, price: 1 }, { time: minuteCandles[7].time, price: 2 }] },
    ];
    render(<EChartsAdapter candles={daily} theme={theme} interval="1d" drawings={drawings} />);
    await waitFor(() => expect(lastGraphic()).toBeTruthy());
    expect(lastGraphic().children.map((c) => c.id)).toEqual(["long"]);
  });

  it("잠금이면 옮기거나 클릭해 지울 수 없다", async () => {
    const drawings: Drawing[] = [{ id: "hl", tool: "HORIZONTAL_LINE", points: [{ time: 0, price: 150 }] }];
    const onChange = vi.fn();
    render(<EChartsAdapter candles={minuteCandles} theme={theme} interval="1m" drawings={drawings} onDrawingsChange={onChange} drawingsLocked />);
    await waitFor(() => expect(lastGraphic()).toBeTruthy());
    const el = lastGraphic().children[0];
    expect(el.draggable).toBe(false);
    expect(el.onclick).toBeUndefined();
  });

  it("클릭(제자리 누름·뗌)하면 지우고, 실제로 끈 뒤의 클릭은 지우지 않고 옮긴다", async () => {
    const drawings: Drawing[] = [{ id: "hl", tool: "HORIZONTAL_LINE", points: [{ time: 0, price: 150 }] }];
    const onChange = vi.fn();
    render(<EChartsAdapter candles={minuteCandles} theme={theme} interval="1m" drawings={drawings} onDrawingsChange={onChange} />);
    await waitFor(() => expect(lastGraphic()).toBeTruthy());
    type Handlers = { ondragstart?: () => void; ondragend: (this: { x: number; y: number }) => void; onclick: () => void };
    const el = lastGraphic().children[0] as unknown as Handlers;

    // zrender: draggable 요소는 움직이지 않아도 mousedown에 dragstart, mouseup에 dragend(x=y=0) → click 순서
    el.ondragstart?.();
    el.ondragend.call({ x: 0, y: 0 });
    el.onclick();
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][0]).toEqual([]);

    // 실제로 끌면(dy=-20px → 가격 +20) 옮기고, 이어지는 click은 무시한다
    onChange.mockClear();
    el.ondragstart?.();
    el.ondragend.call({ x: 0, y: -20 });
    el.onclick();
    expect(onChange).toHaveBeenCalledTimes(1);
    expect((onChange.mock.calls[0][0] as Drawing[])[0].points[0].price).toBe(170);
  });

  it("수평선 — 자석이면 가장 가까운 OHLC 가격에 붙는다", async () => {
    const onChange = vi.fn();
    render(<EChartsAdapter candles={minuteCandles} theme={theme} interval="1m" activeDrawingTool="HORIZONTAL_LINE" magnet onDrawingsChange={onChange} />);
    await waitFor(() => expect(zrHandlers.click).toBeDefined());
    // 인덱스 3 봉: O103 H113 L93 C108 — 111은 고가 113에 붙는다
    act(() => zrHandlers.click(at(3.2, 111)));
    expect(onChange).toHaveBeenCalledTimes(1);
    const [d] = onChange.mock.calls[0][0] as Drawing[];
    expect(d.tool).toBe("HORIZONTAL_LINE");
    expect(d.points).toEqual([{ time: minuteCandles[3].time, price: 113 }]);
  });

  it("추세선 — 두 번 클릭, 사이에 미리보기", async () => {
    const onChange = vi.fn();
    render(<EChartsAdapter candles={minuteCandles} theme={theme} interval="1m" activeDrawingTool="TREND_LINE" onDrawingsChange={onChange} />);
    await waitFor(() => expect(zrHandlers.click).toBeDefined());
    act(() => zrHandlers.click(at(1, 100)));
    act(() => zrHandlers.mousemove(at(4, 130)));
    expect(lastGraphic().children.some((c) => (c.style as { lineDash?: number[] })?.lineDash)).toBe(true);
    act(() => zrHandlers.click(at(4, 130)));
    const [d] = onChange.mock.calls[0][0] as Drawing[];
    expect(d.points).toEqual([{ time: minuteCandles[1].time, price: 100 }, { time: minuteCandles[4].time, price: 130 }]);
  });

  it("측정 — 저장하지 않고 가격·%·봉 수를 표시한다", async () => {
    const onChange = vi.fn();
    render(<EChartsAdapter candles={minuteCandles} theme={theme} interval="1m" activeDrawingTool="MEASURE" onDrawingsChange={onChange} />);
    await waitFor(() => expect(zrHandlers.click).toBeDefined());
    act(() => zrHandlers.click(at(2, 100)));
    act(() => zrHandlers.click(at(7, 110)));
    expect(onChange).not.toHaveBeenCalled();
    const label = lastGraphic().children.find((c) => typeof (c.style as { text?: string })?.text === "string");
    expect((label!.style as { text: string }).text).toBe("+10 (+10.00%) · 5봉");
    expect(label!.style).toMatchObject({ x: 48 + 7 * 10 + 6, align: "left" });
  });

  it("구간 확대 — 두 점 사이로 dataZoom 후 도구 해제", async () => {
    const done = vi.fn();
    render(<EChartsAdapter candles={minuteCandles} theme={theme} interval="1m" activeDrawingTool="ZOOM" onDrawingToolDone={done} />);
    await waitFor(() => expect(zrHandlers.click).toBeDefined());
    act(() => zrHandlers.click(at(7, 100)));
    act(() => zrHandlers.click(at(2, 100)));
    expect(dispatchAction).toHaveBeenCalledWith({ type: "dataZoom", dataZoomIndex: 0, startValue: 2, endValue: 7 });
    expect(done).toHaveBeenCalledTimes(1);
  });

  it("펜 — 끌어서 그린 획을 (시각, 가격)으로 저장한다", async () => {
    const onChange = vi.fn();
    render(<EChartsAdapter candles={minuteCandles} theme={theme} interval="1m" activeDrawingTool="PEN" onDrawingsChange={onChange} />);
    await waitFor(() => expect(zrHandlers.mousedown).toBeDefined());
    // 펜일 때는 드래그 팬을 끈다
    expect(setOption.mock.calls.some((c) => (c[0] as Opt).dataZoom?.[0]?.moveOnMouseMove === false)).toBe(true);
    act(() => zrHandlers.mousedown(at(1, 100)));
    act(() => zrHandlers.mousemove(at(1.5, 105)));
    act(() => zrHandlers.mousemove(at(1.51, 105))); // 3px 미만은 건너뜀
    act(() => zrHandlers.mousemove(at(2, 110)));
    act(() => zrHandlers.mouseup({}));
    const [d] = onChange.mock.calls[0][0] as Drawing[];
    expect(d.tool).toBe("PEN");
    expect(d.points).toEqual([
      { time: minuteCandles[1].time, price: 100 },
      { time: minuteCandles[1].time + 30, price: 105 },
      { time: minuteCandles[2].time, price: 110 },
    ]);
  });

  it("텍스트 — 클릭한 자리에 입력, Enter로 추가, Esc로 취소", async () => {
    const onChange = vi.fn();
    render(<EChartsAdapter candles={minuteCandles} theme={theme} interval="1m" activeDrawingTool="TEXT" onDrawingsChange={onChange} />);
    await waitFor(() => expect(zrHandlers.click).toBeDefined());
    act(() => zrHandlers.click(at(3, 120)));
    const input = screen.getByRole("textbox", { name: /차트에 넣을 텍스트/ });
    fireEvent.keyDown(input, { key: "Escape" });
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(onChange).not.toHaveBeenCalled();

    act(() => zrHandlers.click(at(3, 120)));
    const again = screen.getByRole("textbox", { name: /차트에 넣을 텍스트/ });
    fireEvent.change(again, { target: { value: "  저항 구간  " } });
    fireEvent.keyDown(again, { key: "Enter" });
    const [d] = onChange.mock.calls[0][0] as Drawing[];
    expect(d).toMatchObject({ tool: "TEXT", text: "저항 구간", points: [{ time: minuteCandles[3].time, price: 120 }] });
  });
});
