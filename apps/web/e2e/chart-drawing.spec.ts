import { test, expect, type Page } from "@playwright/test";
import {
  DAILY, MINUTE, INTRADAY_EVENT, DAILY_EVENT, PREFS_KEY,
  mockChartApi, openChart, waitForCandles, toPage, priceAtPageY, pricePerPx, zoomRange, graphicElements, markPoints, renderedTextRect, renderedTexts,
  storedDrawings, expectStoredCount, toolbar,
} from "./support/chart";

/**
 * 종목 상세(/stocks/[symbol]) 차트의 그리기 도구를 실제 브라우저에서 구동한다.
 *
 * 단위 테스트는 echarts를 목으로 바꿔서 검증하므로, 실제 캔버스에서의 끌기·클릭·줌(zrender 이벤트,
 * dataZoom의 드래그 팬, 그래픽 요소 드래그)은 여기서만 확인된다. 봉·이벤트는 /api 가로채기로
 * 고정해 백엔드 없이 결정적으로 돈다(e2e/support/chart.ts).
 *
 * 관찰하는 것: localStorage에 저장된 드로잉(monticker:chartDrawings:v2:...), ECharts 인스턴스의
 * 옵션 상태(줌 구간·그래픽·마커 — 컨테이너 DOM 노드의 __mtChart), 텍스트 입력창 위치.
 */

// 차트가 넉넉히 넓고 화면 안에 다 들어오게(기본 1280×720에서는 차트 아래쪽이 화면 밖으로 나간다)
test.use({ viewport: { width: 1600, height: 1200 } });

test.beforeEach(async ({ page }) => {
  await mockChartApi(page);
});

/** 화면에 보이는 마지막 쪽 봉들 — 기본 줌(최근 30봉) 안에서 고른다 */
const I1 = 40, I2 = 52;
const mid = (i: number) => (DAILY[i].high + DAILY[i].low) / 2;

async function clickAt(page: Page, index: number, price: number) {
  const p = await toPage(page, index, price);
  await page.mouse.click(p.x, p.y);
  return p;
}

async function drag(page: Page, from: { x: number; y: number }, to: { x: number; y: number }, steps = 8) {
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await page.mouse.move(to.x, to.y, { steps });
  await page.mouse.up();
}

test.describe("차트 그리기 도구", () => {
  test("수평선을 그리면 저장되고, 십자선 상태에서 끌어 옮기면 가격이 바뀐다", async ({ page }) => {
    await openChart(page);
    const tb = toolbar(page);

    await tb.hline.click();
    await expect(tb.hline).toHaveAttribute("aria-pressed", "true");
    const at = await clickAt(page, I1, mid(I1));
    await expectStoredCount(page, 1);
    const [drawn] = await storedDrawings(page);
    expect(drawn.tool).toBe("HORIZONTAL_LINE");
    // 마우스 좌표는 정수 픽셀로 반올림돼 전달되므로 1px 만큼의 가격 오차를 허용한다
    const onePx = await pricePerPx(page);
    expect(Math.abs(drawn.points[0].price - (await priceAtPageY(page, at.y)))).toBeLessThanOrEqual(onePx);

    // 십자선으로 돌아와 선을 위로 60px 끈다 → 같은 드로잉의 가격이 그만큼 오른다
    await page.keyboard.press("Escape");
    await expect(tb.cross).toHaveAttribute("aria-pressed", "true");
    const before = drawn.points[0].price;
    const zoomBefore = await zoomRange(page);
    const grab = await toPage(page, I1 - 3, before);
    await drag(page, grab, { x: grab.x + 30, y: grab.y - 60 });
    const expected = await priceAtPageY(page, grab.y - 60);

    await expect.poll(async () => (await storedDrawings(page))[0]?.points[0].price).not.toBe(before);
    const moved = await storedDrawings(page);
    expect(moved).toHaveLength(1);
    expect(moved[0].id).toBe(drawn.id);
    expect(Math.abs(moved[0].points[0].price - expected)).toBeLessThanOrEqual(onePx);
    // 드로잉을 끄는 동안 차트는 패닝되지 않는다
    expect(await zoomRange(page)).toEqual(zoomBefore);
  });

  test("십자선 상태에서 드로잉을 클릭하면 지워진다", async ({ page }) => {
    await openChart(page);
    const tb = toolbar(page);
    await tb.hline.click();
    await clickAt(page, I1, mid(I1));
    await expectStoredCount(page, 1);
    await page.keyboard.press("Escape");
    await expect(tb.trash).toHaveAccessibleName("모두 지우기 (1개)");

    const [d] = await storedDrawings(page);
    await clickAt(page, I1 - 3, d.points[0].price);
    await expectStoredCount(page, 0);
    await expect(tb.trash).toHaveAttribute("aria-disabled", "true");
  });

  test("추세선을 끌어 옮기면 봉 단위로 시각이 옮겨지고 기울기는 유지된다", async ({ page }) => {
    await openChart(page);
    const tb = toolbar(page);
    await tb.trend.click();
    await clickAt(page, I1, DAILY[I1].low);
    await clickAt(page, I2, DAILY[I2].high);
    await expectStoredCount(page, 1);
    const [line] = await storedDrawings(page);
    expect(line.points.map((p) => p.time)).toEqual([DAILY[I1].time, DAILY[I2].time]);

    await page.keyboard.press("Escape");
    // 선의 가운데를 잡고 봉 3개만큼 오른쪽으로
    const a = await toPage(page, I1, line.points[0].price);
    const b = await toPage(page, I2, line.points[1].price);
    const center = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
    const bar = (await toPage(page, 1, 0)).x - (await toPage(page, 0, 0)).x;
    await drag(page, center, { x: center.x + bar * 3, y: center.y });

    await expect.poll(async () => (await storedDrawings(page))[0].points[0].time).toBe(DAILY[I1 + 3].time);
    const [after] = await storedDrawings(page);
    expect(after.points[1].time).toBe(DAILY[I2 + 3].time);
    expect(after.points[1].price - after.points[0].price).toBeCloseTo(line.points[1].price - line.points[0].price, 0);
  });

  test("펜으로 끄는 동안 차트가 패닝되지 않는다 (펜이 아니면 같은 드래그가 패닝한다)", async ({ page }) => {
    await openChart(page);
    const tb = toolbar(page);
    const from = await toPage(page, 45, mid(45));
    const to = { x: from.x + 160, y: from.y + 40 };

    // 대조군: 도구 없이 같은 드래그 → 줌 구간이 왼쪽(과거)으로 이동
    const initial = await zoomRange(page);
    await drag(page, from, to);
    await expect.poll(async () => (await zoomRange(page)).start).toBeLessThan(initial.start);

    const panned = await zoomRange(page);
    await tb.pen.click();
    await expect(tb.pen).toHaveAttribute("aria-pressed", "true");
    await drag(page, from, to, 20);

    await expectStoredCount(page, 1);
    const [pen] = await storedDrawings(page);
    expect(pen.tool).toBe("PEN");
    expect(pen.points.length).toBeGreaterThan(5);
    expect(await zoomRange(page)).toEqual(panned);
  });

  test("텍스트 도구는 클릭한 자리에 입력창을 띄우고 Enter로 한 번만 저장한다", async ({ page }) => {
    await openChart(page);
    const tb = toolbar(page);
    await tb.text.click();
    const at = await clickAt(page, I1, mid(I1));

    const input = page.getByRole("textbox", { name: /차트에 넣을 텍스트/ });
    await expect(input).toBeFocused();
    const box = (await input.boundingBox())!;
    // 입력창의 왼쪽 위가 클릭한 점 바로 위·왼쪽(-4px, -24px)에 놓인다
    expect(Math.abs(box.x - (at.x - 4))).toBeLessThanOrEqual(3);
    expect(Math.abs(box.y - (at.y - 24))).toBeLessThanOrEqual(3);

    await input.fill("지지선 확인");
    await input.press("Enter");
    await expect(input).toBeHidden();
    await expectStoredCount(page, 1);
    const [t] = await storedDrawings(page);
    expect(t).toMatchObject({ tool: "TEXT", text: "지지선 확인" });
    expect(t.points[0].time).toBe(DAILY[I1].time);
    // 저장된 텍스트가 캔버스에 실제로 그려진다 — 옵션뿐 아니라 zrender 표시 목록에서 확인한다
    await expect.poll(() => renderedTexts(page)).toContain("지지선 확인");

    // 입력 중 Esc는 입력만 취소하고 도구는 켜 둔다
    await clickAt(page, I2, mid(I2));
    await expect(input).toBeVisible();
    await input.fill("버릴 글");
    await input.press("Escape");
    await expect(input).toBeHidden();
    await expect(tb.text).toHaveAttribute("aria-pressed", "true");
    await expectStoredCount(page, 1);

    // 도구를 끄고(다시 그리기) 줌해도(또 다시 그리기) 텍스트가 계속 보인다 — 두 번째 다시 그리기부터
    // 텍스트가 화면에서 빠지던 회귀(graphic 그룹 $action: "replace")
    await page.keyboard.press("Escape");
    await expect(tb.cross).toHaveAttribute("aria-pressed", "true");
    const center = await toPage(page, I1, mid(I1));
    await page.mouse.move(center.x, center.y);
    await page.mouse.wheel(0, -200);
    await expect.poll(async () => (await zoomRange(page)).start).toBeGreaterThan(50);
    await expect.poll(() => renderedTexts(page)).toContain("지지선 확인");
  });

  test("측정 도구는 두 점 사이 가격 차·%·봉 수를 차트에 표시하고 저장하지 않는다", async ({ page }) => {
    await openChart(page);
    const tb = toolbar(page);
    await tb.measure.click();
    await clickAt(page, I1, DAILY[I1].close);
    // 두 번째 점을 찍기 전에도 마우스를 따라 미리보기 라벨이 계속 보인다(움직일 때마다 글자가 바뀐다)
    for (const i of [I1 + 3, I1 + 6, I1 + 9]) {
      const p = await toPage(page, i, DAILY[i].close);
      await page.mouse.move(p.x, p.y);
      await expect.poll(async () => (await renderedTexts(page)).some((t) => t.endsWith(`· ${i - I1}봉`))).toBe(true);
    }
    await clickAt(page, I2, DAILY[I2].close);
    await expect.poll(async () => (await renderedTexts(page)).some((t) => t.endsWith(`· ${I2 - I1}봉`))).toBe(true);

    await expect
      .poll(async () => (await graphicElements(page)).find((e) => e.type === "text" && /봉$/.test(e.text ?? ""))?.text)
      .toMatch(new RegExp(`^[+−]?[\\d,]+ \\([+−]?\\d+\\.\\d{2}%\\) · ${I2 - I1}봉$`));
    const label = (await graphicElements(page)).find((e) => e.type === "text" && /봉$/.test(e.text ?? ""))!.text!;
    // 클릭한 픽셀의 가격으로 계산한 값과 같은 부호·크기여야 한다
    const a = await priceAtPageY(page, (await toPage(page, I1, DAILY[I1].close)).y);
    const b = await priceAtPageY(page, (await toPage(page, I2, DAILY[I2].close)).y);
    const pct = ((b - a) / a) * 100;
    const shown = Number(/\(([+−])(\d+\.\d{2})%\)/.exec(label)!.slice(1).join("").replace("−", "-"));
    expect(shown).toBeGreaterThan(0);
    // 1px 반올림 오차 안에서 같은 값
    expect(Math.abs(shown - pct)).toBeLessThanOrEqual(((await pricePerPx(page)) * 2 / a) * 100 + 0.01);
    expect(await storedDrawings(page)).toHaveLength(0);

    // 오른쪽 끝 봉까지 재도 라벨이 오른쪽 가격축 밖으로 잘리지 않는다
    // (맨 마지막 봉은 그리드 경계선 위라 클릭이 그리드 밖으로 판정될 수 있어 바로 앞 봉을 쓴다)
    const lastI = DAILY.length - 2;
    await clickAt(page, I2, DAILY[I2].close);
    await clickAt(page, lastI, DAILY[lastI].high);
    const edgeLabel = `· ${lastI - I2}봉$`;
    await expect.poll(async () => (await renderedTextRect(page, edgeLabel))?.text).toBeTruthy();
    const rect = (await renderedTextRect(page, edgeLabel))!;
    expect(rect.x).toBeGreaterThanOrEqual(0);
    expect(rect.x + rect.width).toBeLessThanOrEqual(rect.chartWidth);

    // Esc → 도구가 꺼지며 측정도 사라진다
    await page.keyboard.press("Escape");
    await expect(tb.cross).toHaveAttribute("aria-pressed", "true");
    await expect.poll(async () => (await graphicElements(page)).filter((e) => e.type === "text").length).toBe(0);
  });

  test("구간 확대는 두 점 사이로 줌하고 십자선으로 돌아간다", async ({ page }) => {
    await openChart(page);
    const tb = toolbar(page);
    await tb.zoom.click();
    await clickAt(page, I2, mid(I2));
    await clickAt(page, I1, mid(I1));

    await expect.poll(async () => {
      const z = await zoomRange(page);
      return [z.startValue, z.endValue];
    }).toEqual([I1, I2]);
    await expect(tb.cross).toHaveAttribute("aria-pressed", "true");
    expect(await storedDrawings(page)).toHaveLength(0);
  });

  test("자석을 켜면 점이 가장 가까운 봉의 시·고·저·종 가격에 붙고, 설정이 기억된다", async ({ page }) => {
    await openChart(page);
    const tb = toolbar(page);
    await tb.magnet.click();
    await expect(tb.magnet).toHaveAttribute("aria-pressed", "true");
    await expect.poll(() => page.evaluate((k) => localStorage.getItem(k), PREFS_KEY)).toContain('"magnet":true');

    const c = DAILY[I1];
    await tb.hline.click();
    // 고가보다 약간 위를 클릭 → 고가에 붙는다
    const at = await toPage(page, I1, c.high);
    await page.mouse.click(at.x, at.y - 4);
    await expectStoredCount(page, 1);
    expect((await storedDrawings(page))[0].points[0].price).toBe(c.high);

    // 저가보다 약간 아래 → 저가
    const lo = await toPage(page, I1 + 1, DAILY[I1 + 1].low);
    await page.mouse.click(lo.x, lo.y + 4);
    await expectStoredCount(page, 2);
    expect((await storedDrawings(page))[1].points[0].price).toBe(DAILY[I1 + 1].low);
  });

  test("잠금 중에는 끌어도 옮겨지지 않고 클릭해도 지워지지 않는다", async ({ page }) => {
    await openChart(page);
    const tb = toolbar(page);
    await tb.hline.click();
    await clickAt(page, I1, mid(I1));
    await expectStoredCount(page, 1);
    await page.keyboard.press("Escape");
    const [d] = await storedDrawings(page);

    await tb.lock.click();
    await expect(tb.lock).toHaveAttribute("aria-pressed", "true");
    await expect(tb.trash).toHaveAttribute("aria-disabled", "true");

    const grab = await toPage(page, I1 - 3, d.points[0].price);
    await drag(page, grab, { x: grab.x, y: grab.y - 60 });
    await page.mouse.click(grab.x, grab.y);
    // 잠금 해제 후 클릭이 지운다는 것을 같은 위치로 확인해, 위 클릭이 선을 정확히 겨눴음을 보장한다
    expect(await storedDrawings(page)).toEqual([d]);

    await tb.lock.click();
    await expect(tb.lock).toHaveAttribute("aria-pressed", "false");
    const again = await toPage(page, I1 - 3, d.points[0].price);
    await page.mouse.click(again.x, again.y);
    await expectStoredCount(page, 0);
  });

  test("Esc는 도구를 끄고 그리던 첫 점을 버린다", async ({ page }) => {
    await openChart(page);
    const tb = toolbar(page);
    await tb.trend.click();
    await clickAt(page, I1, mid(I1));
    await page.keyboard.press("Escape");
    await expect(tb.cross).toHaveAttribute("aria-pressed", "true");
    await expect(tb.trend).toHaveAttribute("aria-pressed", "false");

    // 다시 켜서 한 번 클릭 → 버린 첫 점과 이어지지 않으므로 아직 저장되지 않는다
    await tb.trend.click();
    await clickAt(page, I2, mid(I2));
    expect(await storedDrawings(page)).toHaveLength(0);
    await clickAt(page, I2 + 4, mid(I2 + 4));
    await expectStoredCount(page, 1);
    expect((await storedDrawings(page))[0].points.map((p) => p.time)).toEqual([DAILY[I2].time, DAILY[I2 + 4].time]);
  });

  test("드로잉은 새로고침·봉 간격 전환 뒤에도 남는다", async ({ page }) => {
    await openChart(page);
    const tb = toolbar(page);
    await tb.hline.click();
    await clickAt(page, I1, mid(I1));
    await tb.trend.click();
    await clickAt(page, I1, DAILY[I1].low);
    await clickAt(page, I2, DAILY[I2].high);
    await expectStoredCount(page, 2);
    const saved = await storedDrawings(page);
    const savedIds = (els: { id?: string }[]) => els.map((e) => e.id).filter((id) => saved.some((d) => d.id === id)).sort();

    await page.reload();
    await waitForCandles(page, DAILY.length);
    await expect.poll(async () => savedIds(await graphicElements(page))).toEqual(saved.map((d) => d.id).sort());
    expect(await storedDrawings(page)).toEqual(saved);

    // 1분봉 — 같은 키를 그대로 쓴다. 수평선은 어느 간격에서나 그리고, 일봉 사이에 걸친 추세선도 외삽해 그린다.
    await page.getByRole("button", { name: "1분", exact: true }).click();
    await waitForCandles(page, MINUTE.length, "09-30 09:00");
    expect(await storedDrawings(page)).toEqual(saved);
    await expect.poll(async () => savedIds(await graphicElements(page))).toContain(saved[0].id);

    await page.getByRole("button", { name: "일", exact: true }).click();
    await waitForCandles(page, DAILY.length);
    await expect.poll(async () => savedIds(await graphicElements(page))).toEqual(saved.map((d) => d.id).sort());
    expect(await storedDrawings(page)).toEqual(saved);
  });

  test("장중 이벤트 마커는 1분봉에서 그 시각이 속한 봉에, 일봉에서는 그날 봉에 붙는다", async ({ page }) => {
    await openChart(page);
    await expect.poll(() => markPoints(page)).toEqual(expect.arrayContaining([
      { name: DAILY_EVENT.title, coord: [40, DAILY[40].high] },
      { name: INTRADAY_EVENT.title, coord: [59, DAILY[59].high] },
    ]));

    await page.getByRole("button", { name: "1분", exact: true }).click();
    await waitForCandles(page, MINUTE.length, "09-30 09:00");
    await expect.poll(() => markPoints(page)).toEqual([
      { name: INTRADAY_EVENT.title, coord: [30, MINUTE[30].high] },
    ]);
  });
});
