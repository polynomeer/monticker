import { defineConfig, devices } from "@playwright/test";

// e2e(통합) 테스트: 실제로 뜬 dev/prod 서버를 브라우저로 구동해 검증한다.
// unit 테스트(vitest)는 컴포넌트를 격리해서 목으로 검증하므로, CSP나
// 실제 API/WebSocket 연결처럼 "브라우저 + 서버가 실제로 붙어야" 드러나는
// 버그(예: connect-src 설정 누락)는 e2e에서만 잡힌다.
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? "github" : "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "on-first-retry",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
  ],
  // E2E_BASE_URL이 없으면(로컬) 웹을 직접 띄운다. /stocks/[symbol]은 서버 컴포넌트가 API로 종목을
  // 찾으므로(브라우저 밖이라 page.route로 못 가로챈다) API 자리에 최소 목 서버도 함께 띄운다 —
  // 실제 API가 이미 떠 있으면 그것을 그대로 쓴다(reuseExistingServer). CI는 실제 API + E2E_BASE_URL.
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : [
        {
          command: "node e2e/support/mock-api.mjs",
          url: "http://localhost:8080/api/stocks/search?query=005930",
          reuseExistingServer: true,
          timeout: 10_000,
        },
        {
          command: "pnpm build && pnpm start",
          url: "http://localhost:3000",
          reuseExistingServer: !process.env.CI,
          timeout: 120_000,
        },
      ],
});
