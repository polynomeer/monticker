import { expect, type Page } from "@playwright/test";

/**
 * 차트 드로잉 e2e 공용 — 결정적인 봉·이벤트 목 데이터, /api 가로채기, 캔버스 좌표 헬퍼.
 *
 * 차트는 캔버스라 DOM으로 상태를 읽을 수 없다. EChartsAdapter가 컨테이너
 * (`[data-testid="stock-chart"]`) DOM 노드에 붙여 둔 `__mtChart`(ECharts 인스턴스)로
 * 줌 구간·그래픽(드로잉·측정)·마커를 읽고, convertToPixel로 정확한 클릭 좌표를 계산한다.
 */

/** CI는 실제 API(시드 V12)의 삼성전자를 쓴다 — 로컬은 e2e/support/mock-api.mjs가 어떤 코드든 받아 준다. */
export const SYMBOL = "005930";
/** 로그아웃 상태의 드로잉 저장 키 — chart/drawingStorage.ts */
export const DRAWINGS_KEY = `monticker:chartDrawings:v2:anon:${SYMBOL}`;
export const PREFS_KEY = "monticker:chartPrefs:v1";

const KST = 9 * 3600;
const DAY = 86_400;
/** 2026-09-30 00:00 KST — 마지막 일봉 */
const LAST_DAY = Date.UTC(2026, 8, 30) / 1000 - KST;

export interface Candle { time: number; open: number; high: number; low: number; close: number; volume: number }

function candle(time: number, i: number, base: number, step: number): Candle {
  const mid = base + step * i + Math.round(step * 16 * Math.sin(i / 4));
  const up = i % 3 !== 0;
  const open = up ? mid - step * 4 : mid + step * 4;
  const close = up ? mid + step * 4 : mid - step * 4;
  return { time, open, close, high: Math.max(open, close) + step * 10, low: Math.min(open, close) - step * 10, volume: 100_000 + i * 1_000 };
}

/** 일봉 60개(2026-08-02 … 2026-09-30, KST 자정) */
export const DAILY: Candle[] = Array.from({ length: 60 }, (_, i) => candle(LAST_DAY - (59 - i) * DAY, i, 70_000, 50));
/** 1분봉 60개(2026-09-30 09:00 … 09:59 KST) */
export const MINUTE: Candle[] = Array.from({ length: 60 }, (_, i) => candle(LAST_DAY + 9 * 3600 + i * 60, i, 71_000, 5));

/** 장중 이벤트 — 09:30:40 KST. 1분봉에서는 09:30 봉(인덱스 30), 일봉에서는 그날 봉(59)에 붙어야 한다. */
export const INTRADAY_EVENT = { id: 901, time: LAST_DAY + 9 * 3600 + 30 * 60 + 40, title: "E2E 장중 공시" };
/** 일봉 인덱스 40의 15:00 KST 뉴스 — 1분봉 구간 밖이라 1분봉에서는 보이지 않아야 한다. */
export const DAILY_EVENT = { id: 902, time: DAILY[40].time + 15 * 3600, title: "E2E 일봉 뉴스" };

const iso = (t: number) => new Date(t * 1000).toISOString();

/** 브라우저에서 나가는 /api/* 를 전부 가로챈다(실제 API·네트워크에 의존하지 않게). 모르는 경로는 404. */
export async function mockChartApi(page: Page) {
  // 하단 쿠키 안내 배너가 차트 아래쪽을 덮어 클릭을 가로채지 않게 미리 닫아 둔다(필수 쿠키만 — "declined").
  await page.addInitScript(() => {
    try { if (!localStorage.getItem("cookie_consent")) localStorage.setItem("cookie_consent", "declined"); } catch { /* 무시 */ }
  });
  await page.route(/\/api\//, async (route) => {
    const url = new URL(route.request().url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (/^\/api\/stocks\/\d+\/candles$/.test(url.pathname)) {
      const rows = url.searchParams.get("interval") === "1m" ? MINUTE : DAILY;
      return json(rows.map((c) => ({ ...c, open: String(c.open), high: String(c.high), low: String(c.low), close: String(c.close) })));
    }
    if (/^\/api\/stocks\/\d+\/events$/.test(url.pathname)) {
      return json([
        { id: DAILY_EVENT.id, eventTime: iso(DAILY_EVENT.time), eventType: "NEWS_PUBLISHED", title: DAILY_EVENT.title, importanceScore: 50, sentimentScore: null },
        { id: INTRADAY_EVENT.id, eventTime: iso(INTRADAY_EVENT.time), eventType: "DISCLOSURE_PUBLISHED", title: INTRADAY_EVENT.title, importanceScore: 80, sentimentScore: null },
      ]);
    }
    return json({}, 404);
  });
}

type ChartHandle = {
  getOption(): Record<string, unknown>;
  convertToPixel(finder: Record<string, number>, value: number): number;
  convertFromPixel(finder: Record<string, number>, value: number): number;
  isDisposed(): boolean;
};
declare global {
  interface HTMLDivElement { __mtChart?: ChartHandle }
}

/** /stocks/005930을 열고 차트가 n개 봉으로 그려질 때까지 기다린다. */
export async function openChart(page: Page, candles = DAILY.length) {
  await page.goto(`/stocks/${SYMBOL}`);
  await waitForCandles(page, candles);
  await page.locator('[data-testid="stock-chart"]').scrollIntoViewIfNeeded();
}

/** 차트가 firstLabel로 시작하는 n개 봉으로 그려질 때까지(봉 간격을 바꾼 뒤 새 차트를 기다릴 때) */
export async function waitForCandles(page: Page, n: number, firstLabel?: string) {
  await page.waitForFunction(
    ([count, first]) => {
      const c = document.querySelector<HTMLDivElement>('[data-testid="stock-chart"]')?.__mtChart;
      if (!c || c.isDisposed()) return false;
      const data = (c.getOption() as { xAxis?: { data?: string[] }[] }).xAxis?.[0]?.data;
      return data?.length === count && (first == null || data[0] === first);
    },
    [n, firstLabel ?? null] as const,
  );
}

/** 봉 인덱스·가격 → 페이지 좌표(마우스용). 캔버스 기준 픽셀에 캔버스 위치를 더한다. */
export async function toPage(page: Page, index: number, price: number): Promise<{ x: number; y: number }> {
  const box = await page.locator('[data-testid="stock-chart"] canvas').first().boundingBox();
  if (!box) throw new Error("chart canvas not visible");
  const [px, py] = await page.evaluate(([i, p]) => {
    const c = document.querySelector<HTMLDivElement>('[data-testid="stock-chart"]')!.__mtChart!;
    return [c.convertToPixel({ xAxisIndex: 0 }, i), c.convertToPixel({ yAxisIndex: 0 }, p)];
  }, [index, price] as const);
  return { x: box.x + px, y: box.y + py };
}

/** 페이지 y좌표 → 가격 */
export async function priceAtPageY(page: Page, y: number): Promise<number> {
  const box = await page.locator('[data-testid="stock-chart"] canvas').first().boundingBox();
  if (!box) throw new Error("chart canvas not visible");
  return page.evaluate((py) => {
    const c = document.querySelector<HTMLDivElement>('[data-testid="stock-chart"]')!.__mtChart!;
    return Number(c.convertFromPixel({ yAxisIndex: 0 }, py));
  }, y - box.y);
}

/** 세로 1px에 해당하는 가격 폭 — 마우스 좌표 반올림 오차 허용치 */
export async function pricePerPx(page: Page): Promise<number> {
  return page.evaluate(() => {
    const c = document.querySelector<HTMLDivElement>('[data-testid="stock-chart"]')!.__mtChart!;
    return Math.abs(Number(c.convertFromPixel({ yAxisIndex: 0 }, 200)) - Number(c.convertFromPixel({ yAxisIndex: 0 }, 201)));
  });
}

/** 안쪽(inside) dataZoom이 보여 주는 봉 인덱스 구간 */
export async function zoomRange(page: Page): Promise<{ start: number; end: number; startValue: number; endValue: number }> {
  return page.evaluate(() => {
    const c = document.querySelector<HTMLDivElement>('[data-testid="stock-chart"]')!.__mtChart!;
    const dz = (c.getOption() as { dataZoom: { start: number; end: number; startValue: number; endValue: number }[] }).dataZoom[0];
    return { start: dz.start, end: dz.end, startValue: dz.startValue, endValue: dz.endValue };
  });
}

/** 드로잉 그룹(mt-drawings)에 지금 그려진 요소 — 저장된 드로잉과 미리보기(측정 등)를 모두 포함 */
export async function graphicElements(page: Page): Promise<{ type: string; id?: string; silent?: boolean; text?: string }[]> {
  return page.evaluate(() => {
    const c = document.querySelector<HTMLDivElement>('[data-testid="stock-chart"]')!.__mtChart!;
    type El = { type: string; id?: string; silent?: boolean; style?: { text?: string }; children?: El[] };
    const graphic = (c.getOption() as { graphic?: { elements?: El[] }[] }).graphic ?? [];
    const out: { type: string; id?: string; silent?: boolean; text?: string }[] = [];
    const walk = (els: El[] | undefined, parent?: string) => {
      for (const e of els ?? []) {
        if (e.type === "group") walk(e.children, e.id);
        else if (parent === "mt-drawings" || parent == null) out.push({ type: e.type, id: e.id, silent: e.silent, text: e.style?.text });
      }
    };
    for (const g of graphic) walk(g.elements);
    return out;
  });
}

/**
 * 실제로 캔버스에 그려진 텍스트(zrender 표시 목록)의 화면상 사각형 — 옵션 좌표가 아니라 정렬·여백을
 * 반영한 결과라 "라벨이 잘리지 않는다"를 확인할 수 있다. 차트 폭·높이도 함께 돌려준다.
 */
export async function renderedTextRect(page: Page, pattern: string) {
  return page.evaluate((src) => {
    type Rect = { x: number; y: number; width: number; height: number; applyTransform(m: unknown): void };
    type Disp = { style?: { text?: string }; transform?: unknown; getBoundingRect(): Rect & { clone(): Rect } };
    const c = document.querySelector<HTMLDivElement>('[data-testid="stock-chart"]')!.__mtChart as unknown as {
      getWidth(): number; getHeight(): number; getZr(): { storage: { getDisplayList(update?: boolean): Disp[] } };
    };
    const re = new RegExp(src);
    const el = c.getZr().storage.getDisplayList(true).find((e) => e.style?.text != null && re.test(e.style.text));
    if (!el) return null;
    const r = el.getBoundingRect().clone();
    if (el.transform) r.applyTransform(el.transform);
    return { x: r.x, y: r.y, width: r.width, height: r.height, chartWidth: c.getWidth(), chartHeight: c.getHeight(), text: el.style!.text! };
  }, pattern);
}

/** 지금 캔버스에 실제로 그려지는 글자들(zrender 표시 목록) — 옵션에만 있고 화면에서 빠진 경우를 잡는다 */
export async function renderedTexts(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const c = document.querySelector<HTMLDivElement>('[data-testid="stock-chart"]')!.__mtChart as unknown as {
      getZr(): { storage: { getDisplayList(update?: boolean): { type: string; style?: { text?: string } }[] } };
    };
    return c.getZr().storage.getDisplayList(true).filter((e) => e.type === "tspan" && e.style?.text != null).map((e) => e.style!.text!);
  });
}

export async function markPoints(page: Page): Promise<{ name: string; coord: [number, number] }[]> {
  return page.evaluate(() => {
    const c = document.querySelector<HTMLDivElement>('[data-testid="stock-chart"]')!.__mtChart!;
    const s = (c.getOption() as { series: { markPoint?: { data?: { name: string; coord: [number, number] }[] } }[] }).series[0];
    return (s.markPoint?.data ?? []).map((d) => ({ name: d.name, coord: d.coord }));
  });
}

export interface StoredDrawing { id: string; tool: string; points: { time: number; price: number }[]; text?: string }

export async function storedDrawings(page: Page): Promise<StoredDrawing[]> {
  return page.evaluate((key) => {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as { drawings: StoredDrawing[] }).drawings : [];
  }, DRAWINGS_KEY);
}

export async function expectStoredCount(page: Page, n: number) {
  await expect.poll(async () => (await storedDrawings(page)).length).toBe(n);
}

export function toolbar(page: Page) {
  const bar = page.getByRole("toolbar", { name: "그리기 도구" });
  return {
    cross: bar.getByRole("button", { name: /^십자선/ }),
    trend: bar.getByRole("button", { name: /^추세선/ }),
    hline: bar.getByRole("button", { name: /^수평선/ }),
    pen: bar.getByRole("button", { name: /^펜/ }),
    text: bar.getByRole("button", { name: /^텍스트/ }),
    measure: bar.getByRole("button", { name: /^측정/ }),
    zoom: bar.getByRole("button", { name: /^구간 확대/ }),
    magnet: bar.getByRole("button", { name: /^자석/ }),
    lock: bar.getByRole("button", { name: /그리기 잠금/ }),
    trash: bar.getByRole("button", { name: /^모두 지우기/ }),
  };
}
