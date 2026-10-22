import { test } from "node:test";
import assert from "node:assert/strict";
import { changeColor, dracula, light, palettes, resolveMarketColors, resolveScheme } from "./palette.ts";

test("기본(화면 모드에 맞춤)은 다크 초록 상승, 라이트 빨강 상승", () => {
  assert.equal(resolveMarketColors("dark", "auto").up, "#0ecb81");
  assert.equal(resolveMarketColors("dark", "auto").down, "#f6465d");
  assert.equal(resolveMarketColors("light", "auto").up, "#d0263c");
  assert.equal(resolveMarketColors("light", "auto").down, "#1b64da");
});

test("직접 고른 방식은 두 모드 모두 같은 방향", () => {
  assert.equal(resolveMarketColors("dark", "redUp").up, "#f6465d");
  assert.equal(resolveMarketColors("dark", "redUp").down, "#4c8dff");
  assert.equal(resolveMarketColors("light", "greenUp").up, "#08804f");
  assert.equal(resolveMarketColors("light", "greenUp").down, "#d0263c");
});

test("시스템 따라가기만 기기 값을 본다, 모르면 다크", () => {
  assert.equal(resolveScheme("dark", "light"), "dark");
  assert.equal(resolveScheme("light", "dark"), "light");
  assert.equal(resolveScheme("system", "light"), "light");
  assert.equal(resolveScheme("system", null), "dark");
});

test("등락 0은 보조 색", () => {
  const m = resolveMarketColors("dark", "auto");
  assert.equal(changeColor(1, m, "n"), m.up);
  assert.equal(changeColor(-1, m, "n"), m.down);
  assert.equal(changeColor(0, m, "n"), "n");
});

test("두 팔레트가 같은 토큰을 갖는다", () => {
  assert.deepEqual(Object.keys(dracula).sort(), Object.keys(light).sort());
  assert.equal(palettes.dark, dracula);
});

test("주황(실전)은 공시·주의 색과 겹치지 않는다", () => {
  for (const p of [dracula, light]) {
    assert.notEqual(p.live, p.disclosure);
    assert.notEqual(p.live, p.caution);
    assert.notEqual(p.live, p.danger);
  }
});
