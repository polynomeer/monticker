import { test, expect } from "@playwright/test";

/**
 * 스크리너 홈의 탭/시장/정렬 전환이 실제 브라우저 + 서버 조합에서 정상
 * 동작하는지 검증하는 e2e 스모크 테스트.
 *
 * screener.spec.ts가 최초 로드(및 그 회귀들)를 커버한다면, 이 스펙은 사용자가
 * 화면에서 실제로 하는 다음 행동 — 탭/시장/정렬 필터를 눌러 서로 다른
 * /api/screener 쿼리 파라미터 조합을 실제로 왕복시키는 것 — 을 커버한다.
 * 컴포넌트 단위 Vitest 테스트는 useScreener 훅을 통째로 모킹하므로, 필터를
 * 바꿨을 때 실제 API 호출 파라미터가 어긋나는 문제(예: market/sort 오타,
 * 탭 전환 시 sort 초기화 로직 누락)는 여기서만 잡힌다.
 *
 * 시드 데이터에 의존하지 않는다: 종목 목록이 비어 있어도(빈 DB) 탭/필터
 * 버튼 자체와 헤더, "총 N개 종목" 카운터는 항상 렌더링되므로 그것만 검증한다.
 */
test.describe("스크리너 필터 전환", () => {
  test("탭을 전환해도 에러 없이 헤더가 유지되고 실제 API를 다시 호출한다", async ({ page }) => {
    const consoleErrors: string[] = [];
    page.on("console", (msg) => {
      if (msg.type() === "error") consoleErrors.push(msg.text());
    });

    await page.goto("/");
    await expect(page.getByRole("heading", { name: "스크리너" })).toBeVisible();

    const screenerRequest = page.waitForResponse((res) =>
      /\/api\/screener\?/.test(res.url()) && /tab=movers/.test(res.url())
    );
    await page.getByRole("button", { name: "급등·급락" }).click();
    const res = await screenerRequest;
    expect(res.status()).toBe(200);

    // 탭 전환 시 헤더는 그대로 남아 있어야 한다(페이지 전체가 깨지지 않았다는 증거).
    await expect(page.getByRole("heading", { name: "스크리너" })).toBeVisible();
    await expect(page.getByText(/총 [\d,]*\s*개 종목/)).toBeVisible();

    const cspOrConnectErrors = consoleErrors.filter(
      (e) => /content security policy/i.test(e) || /blocked/i.test(e)
    );
    expect(cspOrConnectErrors, `CSP-blocked requests found:\n${cspOrConnectErrors.join("\n")}`)
      .toHaveLength(0);
  });

  test("시장(해외) 및 정렬(거래량순) 필터가 올바른 쿼리 파라미터로 API를 호출한다", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "스크리너" })).toBeVisible();

    const marketRequest = page.waitForResponse((res) =>
      /\/api\/screener\?/.test(res.url()) && /market=overseas/.test(res.url())
    );
    await page.getByRole("button", { name: "해외" }).click();
    expect((await marketRequest).status()).toBe(200);

    const sortRequest = page.waitForResponse((res) =>
      /\/api\/screener\?/.test(res.url()) && /sort=volume/.test(res.url())
    );
    await page.getByRole("button", { name: "거래량순" }).click();
    expect((await sortRequest).status()).toBe(200);
  });
});
