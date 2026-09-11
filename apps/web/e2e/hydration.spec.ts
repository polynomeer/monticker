import { test, expect, type Page } from "@playwright/test";

/**
 * SSR/CSR 불일치("Hydration failed because the server rendered HTML didn't
 * match the client") 재발 방지 스모크 테스트.
 *
 * 배경: 2026-09-09에 이 증상을 09b8b21(<body>에 suppressHydrationWarning
 * 추가)로 고쳤다고 판단했으나, 2026-09-10에 무관한 작업 중 /stocks/[symbol],
 * /watchlist에서 다시 재현됐다. 재조사 결과 suppressHydrationWarning은 그
 * 엘리먼트 자신의 attribute/text 불일치만 가리며, 자식 노드가 추가/삭제되는
 * 구조적 불일치(예: 브라우저 확장이 <body> 하위에 노드를 주입)는 전혀
 * 가리지 못한다는 걸 로컬에서 직접 재현해 확인했다(layout.tsx 주석 참고).
 * 즉 그 부류의 잡음은 앱 코드로 막을 수 없는 React/Next.js의 알려진 한계다.
 *
 * 이 테스트가 실제로 지키는 것: "우리 코드"가 클라이언트 전용 상태(auth
 * 토큰, localStorage, 테마, 스크롤 위치 등)를 최초 렌더에 새어들게 해서
 * 스스로 SSR/CSR 불일치를 만드는 회귀. 프로덕션 빌드(minify)에서는 React가
 * 이 에러를 "Minified React error #418"로 압축하므로 그 코드와, dev 모드로
 * 돌릴 경우의 원문 문자열을 함께 매칭한다.
 */
const HYDRATION_MISMATCH_PATTERNS = [
  /Minified React error #418/,
  /Hydration failed because the server rendered/i,
  /A tree hydrated but some attributes of the server rendered HTML didn't match/i,
  /Text content does not match server-rendered HTML/i,
];

function isHydrationMismatch(text: string): boolean {
  return HYDRATION_MISMATCH_PATTERNS.some((p) => p.test(text));
}

async function collectHydrationErrors(page: Page): Promise<string[]> {
  const found: string[] = [];
  page.on("console", (msg) => {
    if (msg.type() === "error" && isHydrationMismatch(msg.text())) found.push(msg.text());
  });
  page.on("pageerror", (err) => {
    const text = `${err.message}\n${err.stack ?? ""}`;
    if (isHydrationMismatch(text)) found.push(text);
  });
  return found;
}

test.describe("SSR/CSR 하이드레이션 일치성", () => {
  for (const path of ["/", "/watchlist", "/stocks/__hydration-smoke-test-nonexistent__"]) {
    test(`${path} 최초 로드 시 하이드레이션 불일치가 없다`, async ({ page }) => {
      const hydrationErrors = await collectHydrationErrors(page);

      await page.goto(path);
      // 하이드레이션은 최초 페인트 직후 비동기로 완료되므로 잠깐 기다린다.
      await page.waitForTimeout(1_500);

      expect(
        hydrationErrors,
        `${path}에서 하이드레이션 불일치 발생:\n${hydrationErrors.join("\n---\n")}`
      ).toHaveLength(0);
    });
  }
});
