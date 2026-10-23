import { afterEach, describe, expect, it } from "vitest";
import { act, render, renderHook } from "@testing-library/react";
import { AppThemeSync, MarketColorSync } from "@/components/ThemeProvider";
import { themeInitScript } from "@/components/ThemeScript";
import { CHART_THEMES, THEME_STORAGE_KEY, resolveMarketColors, useThemePalette, useThemeStore } from "@/stores/themeStore";
import { THEMES } from "@/lib/themes";

const root = document.documentElement;

afterEach(() => {
  act(() => useThemeStore.setState({ chartTheme: "default", appTheme: "dracula" }));
  root.removeAttribute("data-theme");
  root.style.removeProperty("--mt-up");
  root.style.removeProperty("--mt-down");
  localStorage.clear();
});

describe("themeStore — 앱 테마", () => {
  it("기본 테마는 드라큘라다", () => {
    expect(useThemeStore.getInitialState().appTheme).toBe("dracula");
  });

  it("모르는 테마 키는 드라큘라로", () => {
    // @ts-expect-error — 저장값이 깨진 경우
    act(() => useThemeStore.getState().setAppTheme("solarized"));
    expect(useThemeStore.getState().appTheme).toBe("dracula");
  });

  it("저장된 깨진 appTheme은 하이드레이션 때 기본으로", async () => {
    localStorage.setItem(THEME_STORAGE_KEY, JSON.stringify({ state: { appTheme: "nope", chartTheme: "korean" }, version: 0 }));
    await useThemeStore.persist.rehydrate();
    expect(useThemeStore.getState().appTheme).toBe("dracula");
    expect(useThemeStore.getState().chartTheme).toBe("korean");
  });
});

describe("useThemePalette — 캔버스 차트 색", () => {
  it("드라큘라 기본, 테마를 바꾸면 그 팔레트로 다시 렌더된다", () => {
    const { result } = renderHook(() => useThemePalette());
    expect(result.current.panel).toBe("#282a36");
    act(() => useThemeStore.getState().setAppTheme("light"));
    expect(result.current).toBe(THEMES.light.palette);
  });
});

describe("resolveMarketColors", () => {
  it("드라큘라 + 글로벌은 이전과 같은 CHART_THEMES.default(같은 참조)", () => {
    expect(resolveMarketColors("default", "dracula")).toBe(CHART_THEMES.default);
  });

  it("라이트 테마 + 글로벌은 테마 기본(상승 빨강·하락 파랑)", () => {
    expect(resolveMarketColors("default", "light")).toMatchObject({ upColor: "#d0263c", downColor: "#1b64da" });
    expect(resolveMarketColors("default", "paper")).toMatchObject({ upColor: "#c62f3d", downColor: "#2160c4" });
  });

  it("사용자가 고른 시세 색상은 테마와 무관하게 유지", () => {
    expect(resolveMarketColors("classic", "light")).toBe(CHART_THEMES.classic);
  });

  it("셀렉터가 같은 참조를 돌려준다(렌더 루프 방지)", () => {
    expect(resolveMarketColors("default", "navy")).toBe(resolveMarketColors("default", "navy"));
  });
});

describe("ThemeProvider 동기화", () => {
  it("AppThemeSync가 html[data-theme]을 단다", () => {
    render(<AppThemeSync />);
    expect(root.dataset.theme).toBe("dracula");
    act(() => useThemeStore.getState().setAppTheme("paper"));
    expect(root.dataset.theme).toBe("paper");
  });

  it("MarketColorSync — 드라큘라는 이전과 같은 값, 라이트 테마는 테마 기본 상승·하락 색", () => {
    render(<MarketColorSync />);
    expect(root.style.getPropertyValue("--mt-up")).toBe("14 203 129");
    expect(root.style.getPropertyValue("--mt-down")).toBe("246 70 93");
    act(() => useThemeStore.getState().setAppTheme("light"));
    expect(root.style.getPropertyValue("--mt-up")).toBe("208 38 60");
    expect(root.style.getPropertyValue("--mt-down")).toBe("27 100 218");
    // 한국식을 고르면 라이트 테마에서도 그 색
    act(() => useThemeStore.getState().setChartTheme("korean"));
    expect(root.style.getPropertyValue("--mt-up")).toBe("255 80 80");
  });
});

describe("ThemeScript — 첫 페인트 전 스크립트", () => {
  const run = () => new Function(themeInitScript())();

  it("저장된 테마를 data-theme에, 글로벌이 아닌 시세 색상을 --mt-up에 단다", () => {
    localStorage.setItem(THEME_STORAGE_KEY, JSON.stringify({ state: { appTheme: "navy", chartTheme: "korean" } }));
    run();
    expect(root.getAttribute("data-theme")).toBe("navy");
    expect(root.style.getPropertyValue("--mt-up")).toBe("255 80 80");
  });

  it("저장값이 없거나 깨졌으면 아무것도 하지 않는다", () => {
    run();
    expect(root.hasAttribute("data-theme")).toBe(false);
    localStorage.setItem(THEME_STORAGE_KEY, "{broken");
    expect(run).not.toThrow();
    localStorage.setItem(THEME_STORAGE_KEY, JSON.stringify({ state: { appTheme: "<x>" } }));
    run();
    expect(root.hasAttribute("data-theme")).toBe(false);
  });
});
