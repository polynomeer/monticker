import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import OrderBook from "@/components/stock/OrderBook";

const book = {
  stockId: 2,
  symbol: "005930",
  currentPrice: 35538.19,   // Mock 생성기가 내는 소수점 현재가
  asks: [{ price: 35550, quantity: 100, amount: 3555000 }],
  bids: [{ price: 35500, quantity: 200, amount: 7100000 }],
};

function renderBook(domestic?: boolean) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <OrderBook stockId={2} prevClose={null} {...(domestic === undefined ? {} : { domestic })} />
    </QueryClientProvider>,
  );
}

describe("호가 가격 표시 — 통화별 자릿수", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(book), { status: 200 })));
  });
  afterEach(() => vi.unstubAllGlobals());

  it("국내 종목은 원화를 정수로 보여 준다", async () => {
    renderBook(true);

    expect(await screen.findByText(/35,538 /)).toBeTruthy();
    expect(screen.queryByText(/35,538\.19/)).toBeNull();
    expect(screen.getByText("원", { exact: false })).toBeTruthy();
  });

  it("해외 종목은 소수점 둘째 자리까지, 스프레드에 원을 붙이지 않는다", async () => {
    renderBook(false);

    expect(await screen.findByText(/35,538\.19/)).toBeTruthy();
    expect(screen.queryByText(/원$/)).toBeNull();
  });
});
