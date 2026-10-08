import { describe, expect, it } from "vitest";
import {
  DRAWING_LIMITS, capDrawings, drawingsStorageKey, loadDrawings, migrateLegacyDrawings, sanitizeDrawing,
  saveDrawings, userScopeFromToken,
} from "@/components/stock/chart/drawingStorage";
import { parseChartPrefs } from "@/components/stock/chart/useChartDrawings";
import type { Drawing } from "@/components/stock/chart/types";

function memoryStorage(initial: Record<string, string> = {}) {
  const m = new Map(Object.entries(initial));
  return {
    map: m,
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => { m.set(k, v); },
    removeItem: (k: string) => { m.delete(k); },
  };
}

const jwt = (payload: object) =>
  `h.${btoa(JSON.stringify(payload)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")}.s`;

const trend: Drawing = { id: "a", tool: "TREND_LINE", points: [{ time: 100, price: 1 }, { time: 200, price: 2 }] };
const text: Drawing = { id: "t", tool: "TEXT", points: [{ time: 100, price: 5 }], text: "지지선" };

describe("drawingStorage", () => {
  it("키는 버전·사용자 범위·종목 코드로 만든다. 사용자 식별자 원문은 남기지 않는다", () => {
    const scope = userScopeFromToken(jwt({ sub: "user@example.com" }));
    expect(scope).toMatch(/^u[0-9a-f]{8}$/);
    expect(scope).toBe(userScopeFromToken(jwt({ sub: "user@example.com" })));
    expect(scope).not.toBe(userScopeFromToken(jwt({ sub: "other@example.com" })));
    expect(drawingsStorageKey(scope, "005930")).toBe(`monticker:chartDrawings:v2:${scope}:005930`);
    expect(drawingsStorageKey(scope, "005930")).not.toContain("example");
    expect(userScopeFromToken(null)).toBe("anon");
    expect(userScopeFromToken("garbage")).toBe("anon");
    expect(userScopeFromToken(jwt({ nosub: 1 }))).toBe("anon");
  });

  it("저장 → 읽기 왕복", () => {
    const s = memoryStorage();
    const key = drawingsStorageKey("anon", "005930");
    const saved = saveDrawings(s, key, [trend, text]);
    expect(saved).toEqual([trend, text]);
    expect(JSON.parse(s.map.get(key)!).v).toBe(2);
    expect(loadDrawings(s, key)).toEqual([trend, text]);
    // 비우면 키를 지운다
    saveDrawings(s, key, []);
    expect(s.map.has(key)).toBe(false);
  });

  it("형식이 틀린 항목은 버리고, 버전이 다르면 읽지 않는다", () => {
    expect(sanitizeDrawing({ id: "x", tool: "CIRCLE", points: [] })).toBeNull();
    expect(sanitizeDrawing({ id: "x", tool: "TREND_LINE", points: [{ time: 1, price: 2 }] })).toBeNull();
    expect(sanitizeDrawing({ id: "x", tool: "HORIZONTAL_LINE", points: [{ time: "1", price: 2 }] })).toBeNull();
    expect(sanitizeDrawing({ id: "x", tool: "TEXT", points: [{ time: 1, price: 2 }], text: "   " })).toBeNull();
    expect(sanitizeDrawing({ id: "x", tool: "HORIZONTAL_LINE", points: [{ time: 1, price: NaN }] })).toBeNull();
    const s = memoryStorage({ k: JSON.stringify({ v: 1, drawings: [trend] }), bad: "{not json" });
    expect(loadDrawings(s, "k")).toEqual([]);
    expect(loadDrawings(s, "bad")).toEqual([]);
    expect(loadDrawings(null, "k")).toEqual([]);
  });

  it("상한 — 개수, 펜 점 수, 글자 수, 전체 크기", () => {
    const many: Drawing[] = Array.from({ length: DRAWING_LIMITS.maxDrawings + 20 }, (_, i) => ({
      id: `h${i}`, tool: "HORIZONTAL_LINE", points: [{ time: i, price: i }],
    }));
    const capped = capDrawings(many);
    expect(capped).toHaveLength(DRAWING_LIMITS.maxDrawings);
    expect(capped[0].id).toBe("h20"); // 오래된 것부터 버림

    const pen = sanitizeDrawing({
      id: "p", tool: "PEN", points: Array.from({ length: 2000 }, (_, i) => ({ time: i, price: i })),
    })!;
    expect(pen.points).toHaveLength(DRAWING_LIMITS.maxPenPoints);

    const long = sanitizeDrawing({ ...text, text: "가".repeat(500) })!;
    expect(long.text).toHaveLength(DRAWING_LIMITS.maxText);

    const bigPens: Drawing[] = Array.from({ length: 40 }, (_, k) => ({
      id: `p${k}`, tool: "PEN", points: Array.from({ length: DRAWING_LIMITS.maxPenPoints }, (_, i) => ({ time: 1_700_000_000 + i, price: 12345.678 + i })),
    }));
    const sized = capDrawings(bigPens);
    expect(JSON.stringify({ v: 2, drawings: sized }).length).toBeLessThanOrEqual(DRAWING_LIMITS.maxChars);
    expect(sized[sized.length - 1].id).toBe("p39");
  });

  it("저장소가 예외를 던져도 목록은 돌려준다", () => {
    const throwing = {
      getItem: () => { throw new Error("blocked"); },
      setItem: () => { throw new Error("quota"); },
      removeItem: () => { throw new Error("blocked"); },
    };
    expect(saveDrawings(throwing, "k", [trend])).toEqual([trend]);
    expect(loadDrawings(throwing, "k")).toEqual([]);
    expect(migrateLegacyDrawings(throwing, 1, ["1d"], "k")).toBeNull();
  });

  it("예전 형식(종목 id·간격별 배열)을 한 번 옮기고 예전 키는 지운다", () => {
    const h: Drawing = { id: "h", tool: "HORIZONTAL_LINE", points: [{ time: 1, price: 9 }] };
    const s = memoryStorage({
      "monticker:chartDrawings:7:1d": JSON.stringify([trend, h]),
      "monticker:chartDrawings:7:1m": JSON.stringify([h]), // 같은 id는 한 번만
    });
    const key = drawingsStorageKey("anon", "005930");
    expect(migrateLegacyDrawings(s, 7, ["1m", "1d"], key)).toEqual([h, trend]);
    expect(s.map.has("monticker:chartDrawings:7:1d")).toBe(false);
    expect(loadDrawings(s, key)).toEqual([h, trend]);
    // 새 키가 있으면 다시 하지 않는다
    expect(migrateLegacyDrawings(s, 7, ["1m", "1d"], key)).toBeNull();
  });

  it("차트 설정 파싱", () => {
    expect(parseChartPrefs(null)).toEqual({ chartType: "candle", magnet: false });
    expect(parseChartPrefs(JSON.stringify({ chartType: "area", magnet: true }))).toEqual({ chartType: "area", magnet: true });
    expect(parseChartPrefs(JSON.stringify({ chartType: "renko" }))).toEqual({ chartType: "candle", magnet: false });
    expect(parseChartPrefs("{")).toEqual({ chartType: "candle", magnet: false });
  });
});
