import type { Config } from "tailwindcss";
import { dracula, tm } from "./src/lib/designTokens";

const config: Config = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        // 값은 src/lib/designTokens.ts 한 곳에 둔다 — 캔버스 차트도 같은 값을 읽는다
        dracula: { ...dracula },
        market: {
          up: "#0ecb81",
          down: "#f6465d",
        },
        // 터미널 디자인 시안(ADR-066) 표면 토큰 — 페이지 < 패널 < 인셋/강조 순서로 밝아진다
        tm: { ...tm },
        // 상승/하락 색 — 사용자 차트 테마(themeStore)를 따라 CSS 변수로 바뀐다
        up: "rgb(var(--mt-up) / <alpha-value>)",
        down: "rgb(var(--mt-down) / <alpha-value>)",
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
