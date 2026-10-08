import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import {
  applyA11y, effectiveReduceMotion, safeLocalStorage, useA11yStore, useReducedMotion,
} from "@/stores/a11yStore";
import { FlashValue } from "@/components/terminal/FlashValue";

const DEFAULTS = { textSize: "normal" as const, highContrast: false, reduceMotion: null, monoNumbers: true, priceFlash: true };

function mockMatchMedia(reduce: boolean) {
  window.matchMedia = vi.fn().mockImplementation((q: string) => ({
    matches: reduce && q.includes("reduce"),
    media: q,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })) as unknown as typeof window.matchMedia;
}

describe("a11yStore html 반영", () => {
  beforeEach(() => {
    useA11yStore.setState(DEFAULTS);
    for (const a of ["data-text-size", "data-contrast", "data-motion", "data-num-font", "data-price-flash"]) {
      document.documentElement.removeAttribute(a);
    }
  });

  it("기본값은 OS 모션 설정을 따르고(data-motion 없음) 숫자 고정폭·깜빡임 켜짐", () => {
    applyA11y(useA11yStore.getState());
    const html = document.documentElement;
    expect(html.getAttribute("data-text-size")).toBe("normal");
    expect(html.hasAttribute("data-motion")).toBe(false);
    expect(html.hasAttribute("data-num-font")).toBe(false);
    expect(html.hasAttribute("data-price-flash")).toBe(false);
  });

  it("작게·움직임 줄이기·숫자 비고정폭·깜빡임 끔을 html 속성으로 반영한다", () => {
    applyA11y({ textSize: "small", highContrast: true, reduceMotion: true, monoNumbers: false, priceFlash: false });
    const html = document.documentElement;
    expect(html.getAttribute("data-text-size")).toBe("small");
    expect(html.getAttribute("data-contrast")).toBe("high");
    expect(html.getAttribute("data-motion")).toBe("reduce");
    expect(html.getAttribute("data-num-font")).toBe("proportional");
    expect(html.getAttribute("data-price-flash")).toBe("off");
  });

  it("사용자가 움직임 줄이기를 끄면 OS 설정을 덮는다(data-motion=full)", () => {
    applyA11y({ ...DEFAULTS, reduceMotion: false });
    expect(document.documentElement.getAttribute("data-motion")).toBe("full");
  });

  it("effectiveReduceMotion — 사용자 설정이 우선, 없으면 OS", () => {
    expect(effectiveReduceMotion(null, true)).toBe(true);
    expect(effectiveReduceMotion(null, false)).toBe(false);
    expect(effectiveReduceMotion(false, true)).toBe(false);
    expect(effectiveReduceMotion(true, false)).toBe(true);
  });

  it("localStorage가 throw해도 저장소 래퍼는 예외를 내지 않는다", () => {
    const get = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("SecurityError"); });
    const set = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("QuotaExceeded"); });
    expect(safeLocalStorage.getItem("monticker-a11y")).toBeNull();
    expect(() => safeLocalStorage.setItem("monticker-a11y", "{}")).not.toThrow();
    get.mockRestore();
    set.mockRestore();
  });
});

describe("useReducedMotion · FlashValue", () => {
  const orig = window.matchMedia;
  afterEach(() => {
    window.matchMedia = orig;
    useA11yStore.setState(DEFAULTS);
  });

  function Probe() {
    return <span>{useReducedMotion() ? "reduce" : "full"}</span>;
  }

  it("사용자 설정이 없으면 OS prefers-reduced-motion을 기본값으로 쓴다", () => {
    mockMatchMedia(true);
    render(<Probe />);
    expect(screen.getByText("reduce")).toBeInTheDocument();
  });

  it("사용자가 끄면 OS 설정보다 우선한다", () => {
    mockMatchMedia(true);
    useA11yStore.setState({ reduceMotion: false });
    render(<Probe />);
    expect(screen.getByText("full")).toBeInTheDocument();
  });

  it("가격이 오르면 상승 깜빡임, 내리면 하락 깜빡임", () => {
    mockMatchMedia(false);
    const { rerender } = render(<FlashValue value={100}>가격</FlashValue>);
    expect(screen.getByText("가격")).not.toHaveAttribute("data-flash");
    rerender(<FlashValue value={101}>가격</FlashValue>);
    expect(screen.getByText("가격")).toHaveAttribute("data-flash", "up");
    expect(screen.getByText("가격")).toHaveClass("tm-flash-up");
    rerender(<FlashValue value={99}>가격</FlashValue>);
    expect(screen.getByText("가격")).toHaveAttribute("data-flash", "down");
  });

  it("깜빡임을 끄거나 움직임 줄이기면 깜빡이지 않는다", () => {
    mockMatchMedia(false);
    useA11yStore.setState({ priceFlash: false });
    const { rerender } = render(<FlashValue value={100}>가격</FlashValue>);
    rerender(<FlashValue value={110}>가격</FlashValue>);
    expect(screen.getByText("가격")).not.toHaveAttribute("data-flash");

    act(() => useA11yStore.setState({ priceFlash: true, reduceMotion: true }));
    rerender(<FlashValue value={120}>가격</FlashValue>);
    expect(screen.getByText("가격")).not.toHaveAttribute("data-flash");
  });
});
