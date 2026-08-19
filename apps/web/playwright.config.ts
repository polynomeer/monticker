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
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: "pnpm build && pnpm start",
        url: "http://localhost:3000",
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
      },
});
