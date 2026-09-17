import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

/** 접근성 — 글자 크기. rem 기반이라 html font-size를 키우면 앱 전체가 비례 확대된다(큰 글씨 모드). */
export type TextSize = "normal" | "large" | "xlarge";

export const TEXT_SIZES: Record<TextSize, { label: string; rootFontSize: string }> = {
  normal: { label: "보통", rootFontSize: "100%" },
  large:  { label: "크게", rootFontSize: "112.5%" },   // 16 → 18px
  xlarge: { label: "더 크게", rootFontSize: "125%" },    // 16 → 20px
};

interface A11yStore {
  textSize: TextSize;
  setTextSize: (v: TextSize) => void;
}

export const useA11yStore = create<A11yStore>()(
  persist(
    (set) => ({
      textSize: "normal",
      setTextSize: (v) => set({ textSize: v }),
    }),
    {
      name: "monticker-a11y",
      storage: createJSONStorage(() => {
        if (typeof window === "undefined") {
          return { getItem: () => null, setItem: () => {}, removeItem: () => {} };
        }
        return localStorage;
      }),
      skipHydration: true,   // themeStore와 동일 — SSR/CSR 불일치 방지, StoreHydrator가 마운트 후 rehydrate
    }
  )
);

/** html[data-text-size]를 세팅한다. globals.css가 이 속성으로 root font-size를 정한다. */
export function applyTextSize(size: TextSize) {
  if (typeof document === "undefined") return;
  document.documentElement.setAttribute("data-text-size", size);
}
