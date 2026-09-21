// 문서용 스크린샷 캡처 — docs/images/*.png 를 재생성한다.
//
//   pnpm --filter @monticker/web exec node scripts/capture-screenshots.mjs [name ...]
//
// 전제: API·Worker·Web이 떠 있고(./dev.sh), EMAIL/PASSWORD 계정이 있으며 그 계좌에
// 모의투자 보유종목·룰셋·백테스트 결과가 이미 있어야 화면이 비어 보이지 않는다.
// 인자로 이름을 주면 그 화면만 다시 찍는다. 환경변수로 대상 서버를 바꿀 수 있다.
import { chromium } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.BASE ?? "http://localhost:3000";
const API = process.env.API ?? "http://localhost:8080";
const OUT = process.env.OUT ?? path.resolve(here, "../../../docs/images");
const EMAIL = process.env.EMAIL ?? "docs-shot@monticker.local";
const PASSWORD = process.env.PASSWORD ?? "Docs12345";
const DETAIL_SYMBOL = process.env.DETAIL_SYMBOL ?? "028260";
fs.mkdirSync(OUT, { recursive: true });

const only = process.argv.slice(2);

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 1,
  colorScheme: "dark",
  locale: "ko-KR",
});
const page = await ctx.newPage();
await page.addInitScript(() => {
  try { localStorage.setItem("cookie_consent", "declined"); } catch {}
  // Next.js dev 오버레이(좌하단 N 버튼)는 캡처에서 숨긴다
  const st = document.createElement("style");
  st.textContent = "nextjs-portal{display:none!important}";
  document.addEventListener("DOMContentLoaded", () => document.head.appendChild(st));
});

const clickText = async (t) => {
  const l = page.getByText(t, { exact: true }).first();
  if (await l.count()) await l.click();
};

async function shot(name, route, { wait = 2500, height = 900, before } = {}) {
  if (only.length && !only.includes(name)) return;
  await page.setViewportSize({ width: 1440, height });
  await page.goto(BASE + route, { waitUntil: "networkidle", timeout: 60000 }).catch(() => {});
  await page.waitForTimeout(800);
  // ECharts는 window resize 이벤트로 컨테이너 폭을 다시 잰다
  await page.evaluate(() => window.dispatchEvent(new Event("resize")));
  if (before) await before();
  await page.waitForTimeout(wait);
  await page.evaluate(() => window.dispatchEvent(new Event("resize")));
  await page.waitForTimeout(600);
  await page.screenshot({ path: path.join(OUT, `${name}.png`) });
  console.log("captured", name);
}

// 로그인 (이메일/비밀번호 폼)
await page.goto(BASE + "/login", { waitUntil: "networkidle" });
await page.fill("#email", EMAIL);
await page.fill("#password", PASSWORD);
await page.click("button[type=submit]");
await page.waitForURL((u) => !u.pathname.startsWith("/login"), { timeout: 20000 });

// 첫 번째 룰셋 id — 백테스트 상세 화면용
const token = await page.evaluate(async (api) => {
  const r = await fetch(api + "/api/auth/refresh", { method: "POST", credentials: "include" });
  return r.ok ? (await r.json()).accessToken : null;
}, API);
let rulesetId = process.env.RULESET_ID ?? null;
if (!rulesetId && token) {
  const r = await fetch(API + "/api/quant/rulesets", { headers: { Authorization: "Bearer " + token } });
  if (r.ok) rulesetId = (await r.json())[0]?.id ?? null;
}

await shot("home", "/", { wait: 4000, height: 1000 });
await shot("stock-detail", "/stocks/" + DETAIL_SYMBOL, { wait: 4000, height: 1100, before: async () => {
  await clickText("1년"); await page.waitForTimeout(2000);
  await clickText("이벤트"); await page.waitForTimeout(800);
}});
await shot("quant-lab", "/quant-lab", { wait: 2500 });
if (rulesetId) await shot("quant-lab-backtest", "/quant-lab/" + rulesetId, { wait: 4000, height: 1200 });
await shot("quant-lab-builder", "/quant-lab/builder", { wait: 2500, height: 1100 });
await shot("wallet", "/wallet", { wait: 3000, height: 1000 });
await shot("wallet-timeline", "/wallet", { wait: 3000, height: 1000, before: async () => { await clickText("원장 타임라인"); await page.waitForTimeout(1500); } });
await shot("wallet-score", "/wallet", { wait: 3000, height: 1000, before: async () => { await clickText("투자 점수"); await page.waitForTimeout(1500); } });
await shot("matching", "/matching", { wait: 3000, height: 1000 });
await shot("risk", "/risk", { wait: 3000, height: 1000 });
await shot("analytics", "/analytics", { wait: 3000, height: 1100, before: async () => { await clickText("최적 비중 계산"); await page.waitForTimeout(4000); } });
await shot("analytics-regime", "/analytics", { wait: 3000, height: 1000, before: async () => { await clickText("시장 국면"); await page.waitForTimeout(3000); } });
await shot("brokerage", "/brokerage", { wait: 3500, height: 1000 });
await shot("brokerage-orders", "/brokerage/orders", { wait: 3000, height: 1000 });
await shot("brokerage-conditional", "/brokerage/conditional-orders", { wait: 3000, height: 1000 });
await shot("brokerage-rebalance", "/brokerage/rebalance", { wait: 3000, height: 1000 });
await shot("portfolio", "/portfolio", { wait: 3500, height: 1000 });
await shot("watchlist", "/watchlist", { wait: 3000 });
await shot("alerts", "/alerts", { wait: 3000 });
await shot("backtest", "/backtest", { wait: 3000, height: 1000 });
await shot("settlement", "/settlement", { wait: 3000 });
await shot("subscription", "/subscription", { wait: 3000, height: 1000 });

await browser.close();
