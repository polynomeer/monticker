import { useEffect, useState } from "react";
import { create } from "zustand";
import { persist, createJSONStorage, type StateStorage } from "zustand/middleware";

/** 접근성 — 글자 크기. rem 기반이라 html font-size를 바꾸면 앱 전체가 비례해 커지거나 작아진다. */
export type TextSize = "small" | "normal" | "large" | "xlarge";

export const TEXT_SIZES: Record<TextSize, { label: string; rootFontSize: string }> = {
  small:  { label: "작게", rootFontSize: "87.5%" },    // 16 → 14px
  normal: { label: "보통", rootFontSize: "100%" },
  large:  { label: "크게", rootFontSize: "112.5%" },   // 16 → 18px
  xlarge: { label: "더 크게", rootFontSize: "125%" },    // 16 → 20px
};

interface A11yStore {
  textSize: TextSize;
  setTextSize: (v: TextSize) => void;
  highContrast: boolean;
  setHighContrast: (v: boolean) => void;
  /** 움직임 줄이기. null이면 OS 설정(prefers-reduced-motion)을 따른다 */
  reduceMotion: boolean | null;
  setReduceMotion: (v: boolean | null) => void;
  /** 숫자(.num) 고정폭 글꼴. 끄면 본문 글꼴 + tabular-nums */
  monoNumbers: boolean;
  setMonoNumbers: (v: boolean) => void;
  /** 실시간 가격이 바뀔 때 잠깐 강조. 움직임 줄이기가 켜져 있으면 무시된다 */
  priceFlash: boolean;
  setPriceFlash: (v: boolean) => void;
}

const noopStorage: StateStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };

/** localStorage 접근은 사파리 비공개 창·차단된 사이트 데이터에서 throw할 수 있다 — 실패하면 기본값으로 동작 */
export const safeLocalStorage: StateStorage = {
  getItem: (k) => { try { return window.localStorage.getItem(k); } catch { return null; } },
  setItem: (k, v) => { try { window.localStorage.setItem(k, v); } catch { /* 저장 불가 — 이번 세션만 유지 */ } },
  removeItem: (k) => { try { window.localStorage.removeItem(k); } catch { /* ignore */ } },
};

export const useA11yStore = create<A11yStore>()(
  persist(
    (set) => ({
      textSize: "normal",
      setTextSize: (v) => set({ textSize: v }),
      highContrast: false,
      setHighContrast: (v) => set({ highContrast: v }),
      reduceMotion: null,
      setReduceMotion: (v) => set({ reduceMotion: v }),
      monoNumbers: true,
      setMonoNumbers: (v) => set({ monoNumbers: v }),
      priceFlash: true,
      setPriceFlash: (v) => set({ priceFlash: v }),
    }),
    {
      name: "monticker-a11y",
      storage: createJSONStorage(() => (typeof window === "undefined" ? noopStorage : safeLocalStorage)),
      partialize: (s) => ({
        textSize: s.textSize,
        highContrast: s.highContrast,
        reduceMotion: s.reduceMotion,
        monoNumbers: s.monoNumbers,
        priceFlash: s.priceFlash,
      }),
      skipHydration: true,   // themeStore와 동일 — SSR/CSR 불일치 방지, StoreHydrator가 마운트 후 rehydrate
    }
  )
);

const REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)";

/** OS의 prefers-reduced-motion. matchMedia가 없으면(SSR·구형 환경) false */
export function osPrefersReducedMotion(): boolean {
  try {
    return typeof window !== "undefined" && typeof window.matchMedia === "function" && window.matchMedia(REDUCED_MOTION_QUERY).matches;
  } catch {
    return false;
  }
}

/** 사용자 설정이 있으면 그것, 없으면 OS 설정 */
export function effectiveReduceMotion(pref: boolean | null, os: boolean): boolean {
  return pref ?? os;
}

/** 실제로 움직임을 줄여야 하는지 — 사용자 설정 우선, 없으면 OS 설정을 따르고 OS 변경도 반영한다 */
export function useReducedMotion(): boolean {
  const pref = useA11yStore((s) => s.reduceMotion);
  const [os, setOs] = useState(false);
  useEffect(() => {
    setOs(osPrefersReducedMotion());
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return;
    const mq = window.matchMedia(REDUCED_MOTION_QUERY);
    const h = () => setOs(mq.matches);
    mq.addEventListener?.("change", h);
    return () => mq.removeEventListener?.("change", h);
  }, []);
  return effectiveReduceMotion(pref, os);
}

/** 가격 깜빡임을 켤지 — 사용자가 켰고 움직임 줄이기가 아닐 때만 */
export function usePriceFlashEnabled(): boolean {
  const on = useA11yStore((s) => s.priceFlash);
  const reduce = useReducedMotion();
  return on && !reduce;
}

/** html[data-text-size]를 세팅한다. globals.css가 이 속성으로 root font-size를 정한다. */
export function applyTextSize(size: TextSize) {
  if (typeof document === "undefined") return;
  document.documentElement.setAttribute("data-text-size", size);
}

/** html[data-contrast]를 세팅한다. globals.css가 이 속성으로 저대비 토큰(뮤트 텍스트·테두리)을 고대비로 덮는다. */
export function applyContrast(high: boolean) {
  if (typeof document === "undefined") return;
  if (high) document.documentElement.setAttribute("data-contrast", "high");
  else document.documentElement.removeAttribute("data-contrast");
}

/**
 * html[data-motion]·[data-num-font]·[data-price-flash]를 세팅한다.
 * - data-motion: "reduce"(사용자가 켬) | "full"(사용자가 끔 — OS 설정을 덮는다) | 없음(OS 설정을 따름, CSS 미디어 쿼리)
 * - data-num-font="proportional": .num을 본문 글꼴로(숫자 폭은 tabular-nums로 계속 맞춘다)
 * - data-price-flash="off": 가격 깜빡임 클래스를 무력화
 */
export function applyMotionAndNumbers(st: Pick<A11yStore, "reduceMotion" | "monoNumbers" | "priceFlash">) {
  if (typeof document === "undefined") return;
  const root = document.documentElement;
  if (st.reduceMotion === null) root.removeAttribute("data-motion");
  else root.setAttribute("data-motion", st.reduceMotion ? "reduce" : "full");
  if (st.monoNumbers) root.removeAttribute("data-num-font");
  else root.setAttribute("data-num-font", "proportional");
  if (st.priceFlash) root.removeAttribute("data-price-flash");
  else root.setAttribute("data-price-flash", "off");
}

/** 스토어 전체를 html에 반영 */
export function applyA11y(st: Pick<A11yStore, "textSize" | "highContrast" | "reduceMotion" | "monoNumbers" | "priceFlash">) {
  applyTextSize(st.textSize);
  applyContrast(st.highContrast);
  applyMotionAndNumbers(st);
}
