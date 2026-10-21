import type { Config } from "tailwindcss";
import { themeColorVar as v } from "./src/lib/themes";

const config: Config = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  // darkMode는 globals.css의 @custom-variant dark로 옮겼다(Tailwind v4).
  theme: {
    extend: {
      colors: {
        // 값은 CSS 변수(globals.css :root = 드라큘라, html[data-theme]가 덮음)라 테마를 바꾸면 클래스 그대로 색이 바뀐다.
        // 드라큘라 hex는 src/lib/designTokens.ts, 테마별 값은 src/lib/themes.ts — 캔버스 차트는 거기서 읽는다.
        dracula: {
          bg: v("panel"),
          surface: v("inner"),
          line: v("line2"),
          comment: v("comment"),
          fg: v("fg"),
          purple: v("accent"),
          pink: v("pink"),
          green: v("green"),
          cyan: v("cyan"),
          orange: v("orange"),
          red: v("red"),
          yellow: v("yellow"),
        },
        market: {
          up: "#0ecb81",
          down: "#f6465d",
        },
        // 터미널 디자인 시안(ADR-066) 표면 토큰 — 페이지 < 패널 < 인셋/강조 순서로 밝아진다. on-accent는 강조색 버튼 위 글자
        tm: {
          page: v("page"),
          panel: v("panel"),
          inner: v("inner"),
          raised: v("raised"),
          line: v("line"),
          line2: v("line2"),
          soft: v("soft"),
          muted: v("muted"),
          "on-accent": v("onAccent"),
        },
        // 의미 색(ADR-112) — text-live·bg-caution/15·border-disclosure/40. 주황(live)은 실전 전용이다.
        live: v("orange"),
        caution: v("yellow"),
        disclosure: v("cyan"),
        // 상승/하락 색 — 사용자 차트 테마(themeStore)를 따라 CSS 변수로 바뀐다.
        // <alpha-value>는 쓰지 않는다: Tailwind v4 호환 계층은 이를 1로 바꾸고 투명도 수식어(bg-up/5)를 버린다.
        // 자리표시자가 없으면 v4가 color-mix로 투명도를 입힌다.
        up: "rgb(var(--mt-up))",
        down: "rgb(var(--mt-down))",
      },
      fontFamily: {
        sans: [
          "Pretendard Variable",
          "Pretendard",
          "-apple-system",
          "BlinkMacSystemFont",
          "system-ui",
          "sans-serif",
        ],
      },
      fontSize: {
        // 시안의 px 크기를 rem으로 — 접근성 글자 크기(html font-size)에 비례 확대된다
        "2xs": ["0.6875rem", { lineHeight: "1rem" }],
        "13": ["0.8125rem", { lineHeight: "1.25rem" }],
        "15": ["0.9375rem", { lineHeight: "1.4rem" }],
      },
      boxShadow: {
        "glow-purple": "0 8px 30px -8px rgba(189, 147, 249, 0.35)",
        "glow-line": "0 8px 30px -12px rgba(0, 0, 0, 0.5)",
        "bezel-inset": "inset 0 1px 1px rgba(248, 248, 242, 0.06)",
        "bezel-inset-light": "inset 0 1px 1px rgba(255, 255, 255, 0.8)",
      },
      transitionTimingFunction: {
        spring: "cubic-bezier(0.16, 1, 0.3, 1)",
      },
      backgroundImage: {
        "mesh-dark":
          "radial-gradient(60% 50% at 15% 0%, rgba(189,147,249,0.10) 0%, rgba(189,147,249,0) 60%), radial-gradient(50% 40% at 100% 0%, rgba(139,233,253,0.08) 0%, rgba(139,233,253,0) 60%)",
        "mesh-light":
          "radial-gradient(60% 50% at 15% 0%, rgba(37,99,235,0.06) 0%, rgba(37,99,235,0) 60%), radial-gradient(50% 40% at 100% 0%, rgba(139,233,253,0.10) 0%, rgba(139,233,253,0) 60%)",
      },
      keyframes: {
        fadeUp: {
          from: { opacity: "0", transform: "translateY(8px)", filter: "blur(4px)" },
          to: { opacity: "1", transform: "translateY(0)", filter: "blur(0)" },
        },
        shimmer: {
          from: { backgroundPosition: "-200% 0" },
          to: { backgroundPosition: "200% 0" },
        },
        popIn: {
          from: { opacity: "0", transform: "scale(0.96) translateY(4px)" },
          to: { opacity: "1", transform: "scale(1) translateY(0)" },
        },
        marquee: {
          from: { transform: "translateX(0)" },
          to: { transform: "translateX(-50%)" },
        },
      },
      animation: {
        "fade-up": "fadeUp 0.5s cubic-bezier(0.16, 1, 0.3, 1) both",
        shimmer: "shimmer 2s linear infinite",
        "pop-in": "popIn 0.35s cubic-bezier(0.16, 1, 0.3, 1) both",
        marquee: "marquee 30s linear infinite",
      },
    },
  },
  plugins: [],
};

export default config;
