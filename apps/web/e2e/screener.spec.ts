import { test, expect } from "@playwright/test";

/**
 * 홈(스크리너) 페이지가 실제 브라우저 + 서버 조합에서 정상 동작하는지 검증하는
 * 통합(e2e) 스모크 테스트.
 *
 * 이 테스트가 지키는 회귀:
 * 1) CSP connect-src에 API 오리진이 없으면 SockJS 핸드셰이크가 막혀 실시간
 *    연결이 "연결 중..."에서 멈춘다 (next.config.ts 참고).
 * 2) Redis 캐시 직렬화 버그가 있으면 /api/screener가 간헐적으로 500을 던진다
 *    (CacheConfig.kt 참고). 여러 번 반복 요청해 이 회귀를 잡는다.
 */
test.describe("스크리너 홈", () => {
  test("에러 없이 로드되고 종목 목록을 렌더링한다", async ({ page }) => {
    const consoleErrors: string[] = [];
    page.on("console", (msg) => {
      if (msg.type() === "error") consoleErrors.push(msg.text());
    });

    await page.goto("/");
    await expect(page.getByRole("heading", { name: "스크리너" })).toBeVisible();
    // SockJS 핸드셰이크(및 그 실패)는 로드 직후 비동기로 일어나므로 잠깐 기다린다.
    await page.waitForTimeout(2_000);

    const cspOrConnectErrors = consoleErrors.filter(
      (e) => /content security policy/i.test(e) || /blocked/i.test(e),
    );
    expect(cspOrConnectErrors, `CSP-blocked requests found:\n${cspOrConnectErrors.join("\n")}`)
      .toHaveLength(0);
  });

  test("/api/screener는 반복 요청에도 500을 던지지 않는다 (캐시 직렬화 회귀)", async ({ request }) => {
    for (let i = 0; i < 8; i++) {
      const res = await request.get(
        "/api/screener?tab=realtime&market=all&sort=amount&limit=20&offset=0",
      );
      expect(res.status(), `attempt #${i + 1}`).toBe(200);
    }
  });
});
