import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import PriceCard from "@/components/stock/PriceCard";

const mockFetch = vi.fn();
global.fetch = mockFetch;

const searchResult  = [{ id: 1, symbol: "005930", name: "삼성전자" }];
const priceResult   = { stockId: 1, symbol: "005930", price: 74000, volume: 12345678, tradeTime: "2026-07-23T10:00:00Z", hasData: true };
const noPriceResult = { stockId: 1, symbol: "005930", price: null, volume: null, tradeTime: null, hasData: false };

function mockOk(data: unknown) {
  return Promise.resolve({ ok: true, json: () => Promise.resolve(data) } as Response);
}

beforeEach(() => { mockFetch.mockReset(); });

describe("PriceCard", () => {
  it("정상 시세를 렌더링한다", async () => {
    mockFetch
      .mockResolvedValueOnce(await mockOk(searchResult))
      .mockResolvedValueOnce(await mockOk(priceResult));

    render(<PriceCard symbol="005930" />);
    await waitFor(() => expect(screen.getByText("74,000")).toBeInTheDocument());
    expect(screen.getByText(/거래량/)).toBeInTheDocument();
  });

  it("데이터 없을 때 안내 문구를 표시한다", async () => {
    mockFetch
      .mockResolvedValueOnce(await mockOk(searchResult))
      .mockResolvedValueOnce(await mockOk(noPriceResult));

    render(<PriceCard symbol="005930" />);
    await waitFor(() => expect(screen.getByText(/시세 데이터 없음/)).toBeInTheDocument());
  });

  it("검색 결과 없으면 시세 데이터 없음 표시", async () => {
    mockFetch.mockResolvedValueOnce(await mockOk([]));

    render(<PriceCard symbol="UNKNOWN" />);
    await waitFor(() => expect(screen.getByText(/시세 데이터 없음/)).toBeInTheDocument());
  });

  it("API 오류 시 시세 데이터 없음 표시", async () => {
    mockFetch.mockRejectedValue(new Error("network error"));
    render(<PriceCard symbol="005930" />);
    await waitFor(() => expect(screen.getByText(/시세 데이터 없음/)).toBeInTheDocument());
  });
});
