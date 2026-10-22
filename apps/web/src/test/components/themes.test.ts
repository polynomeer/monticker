import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { dracula, role, tm } from "@/lib/designTokens";
import { APP_THEME_KEYS, THEMES, hexToChannels, readThemeColor, themeVarName, type ThemeColorToken } from "@/lib/themes";

const css = readFileSync(path.resolve(__dirname, "../../app/globals.css"), "utf8");

/** globals.css에서 선택자 블록의 변수 → 채널 값 */
function varsOf(selector: string): Record<string, string> {
  const start = css.indexOf(`${selector} {`);
  expect(start, `${selector} 블록이 globals.css에 없다`).toBeGreaterThanOrEqual(0);
  const body = css.slice(start, css.indexOf("}", start));
  const out: Record<string, string> = {};
  for (const m of body.matchAll(/(--[a-z0-9-]+):\s*([\d ]+);/g)) out[m[1]] = m[2].trim();
  return out;
}

describe("테마 CSS 변수 ↔ themes.ts", () => {
  it.each(APP_THEME_KEYS)("%s 블록이 팔레트와 한 칸씩 같다", (key) => {
    const vars = varsOf(key === "dracula" ? ":root" : `html[data-theme="${key}"]`);
    const palette = THEMES[key].palette;
    const expected = Object.fromEntries(
      (Object.keys(palette) as ThemeColorToken[]).map((t) => [themeVarName(t), hexToChannels(palette[t])]),
    );
    expect(vars).toEqual(expected);
  });

  it("드라큘라 기본 팔레트는 designTokens hex 그대로다(기본 화면이 이전과 같다)", () => {
    const p = THEMES.dracula.palette;
    expect([p.page, p.inner, p.panel, p.raised, p.line, p.line2, p.soft, p.muted]).toEqual([
      tm.page, tm.inner, tm.panel, tm.raised, tm.line, tm.line2, tm.soft, tm.muted,
    ]);
    // dracula-* 클래스가 읽는 변수도 예전 hex와 같다
    expect(p.panel).toBe(dracula.bg);
    expect(p.inner).toBe(dracula.surface);
    expect(p.line2).toBe(dracula.line);
    expect(p.accent).toBe(dracula.purple);
    expect([p.orange, p.yellow, p.cyan]).toEqual([role.live, role.caution, role.disclosure]);
  });

  it("readThemeColor — CSS 변수가 없으면(jsdom) 드라큘라 값, 있으면 그 값", () => {
    expect(readThemeColor("panel")).toBe("#282a36");
    document.documentElement.style.setProperty("--tm-panel", "255 255 255");
    expect(readThemeColor("panel")).toBe("rgb(255, 255, 255)");
    document.documentElement.style.removeProperty("--tm-panel");
  });
});
