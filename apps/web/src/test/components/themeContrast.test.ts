import { describe, expect, it } from "vitest";
import { THEMES, type ThemePalette } from "@/lib/themes";

function channels(hex: string) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
function lum(rgb: number[]) {
  const [r, g, b] = rgb.map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function ratio(a: number[], b: number[]) {
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}
const cr = (a: string, b: string) => ratio(channels(a), channels(b));
/** globals.css의 다크 테마 .text-down — 하락 색 85% + 흰색 15% */
const downText = (p: ThemePalette, light: boolean) =>
  light ? channels(p.down) : channels(p.down).map((c) => c * 0.85 + 255 * 0.15);

describe.each(Object.entries(THEMES))("테마 대비 — %s", (_key, theme) => {
  const p = theme.palette;

  it("글자·의미 색은 패널 위 4.5:1 이상", () => {
    for (const t of [p.fg, p.soft, p.muted, p.up, p.danger, p.accent, p.yellow, p.orange, p.cyan]) {
      expect(cr(t, p.panel)).toBeGreaterThanOrEqual(4.5);
    }
  });

  it("보조 글자는 입력 바탕 위에서도 4.5:1 이상", () => {
    expect(cr(p.muted, p.inner)).toBeGreaterThanOrEqual(4.5);
  });

  it("작은 하락 숫자는 패널 위 4.5:1 이상", () => {
    expect(ratio(downText(p, theme.light), channels(p.panel))).toBeGreaterThanOrEqual(4.5);
  });

  it("강조 버튼 글자는 4.5:1 이상", () => {
    expect(cr(p.onAccent, p.accent)).toBeGreaterThanOrEqual(4.5);
  });

  it("입력칸 테두리는 입력 바탕 대비 3:1 이상", () => {
    expect(cr(p.control, p.inner)).toBeGreaterThanOrEqual(3);
  });
});
