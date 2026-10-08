// 로컬 e2e 전용 최소 API — 백엔드 없이 /stocks/[symbol]을 띄울 때 쓴다.
//
// /stocks/[symbol]은 서버 컴포넌트가 NEXT_PUBLIC_API_URL의 /api/stocks/search로 종목 id를 찾는다.
// 이 호출은 Next 서버에서 나가므로 Playwright의 page.route로 가로챌 수 없다 — 그래서 로컬에서는
// 이 서버가 대신 답한다. 브라우저에서 나가는 /api/* 호출은 각 스펙이 page.route로 목킹한다.
// CI(e2e-ci.yml)는 실제 API를 띄우고 E2E_BASE_URL을 주므로 이 서버를 쓰지 않는다(시드 종목 005930).
import { createServer } from "node:http";

const port = Number(process.env.MOCK_API_PORT ?? 8080);

createServer((req, res) => {
  const url = new URL(req.url ?? "/", `http://localhost:${port}`);
  if (url.pathname === "/api/stocks/search") {
    const symbol = url.searchParams.get("query") ?? "";
    const body = symbol ? [{ id: 1, symbol, name: "E2E 테스트 종목", market: "KOSPI" }] : [];
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify(body));
    return;
  }
  res.writeHead(404, { "content-type": "application/json" });
  res.end("{}");
}).listen(port, () => console.log(`mock api on :${port}`));
