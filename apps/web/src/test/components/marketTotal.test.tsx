import { describe, it, expect, vi, beforeEach } from "vitest";
import type { ReactNode } from "react";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { hasNextPage, sharedTotalLabel, useMarketTotal } from "@/components/strategy-market/marketTotal";

const authFetch = vi.fn();
vi.mock("@/services/api", () => ({ authFetch: (...a: unknown[]) => authFetch(...a) }));

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

describe("전략 마켓 공유 전략 총수", () => {
  beforeEach(() => authFetch.mockReset());

  it("서버 총수를 그대로 표시한다 — 더는 'N+개'로 추정하지 않는다", () => {
    expect(sharedTotalLabel(37)).toBe("37개");
    expect(sharedTotalLabel(0)).toBe("0개");
    expect(sharedTotalLabel(1234)).toBe("1,234개");
    expect(sharedTotalLabel(undefined)).toBe("—");
  });

  it("count API 응답의 total을 읽는다", async () => {
    authFetch.mockResolvedValue({ ok: true, json: async () => ({ total: 42 }) });
    const { result } = renderHook(() => useMarketTotal(), { wrapper });
    await waitFor(() => expect(result.current.data).toBe(42));
    expect(authFetch).toHaveBeenCalledWith("/api/quant/market/count");
  });

  it("실패하거나 형식이 틀리면 값 없이 둔다", async () => {
    authFetch.mockResolvedValue({ ok: true, json: async () => ({ count: 42 }) });
    const { result } = renderHook(() => useMarketTotal(), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(sharedTotalLabel(result.current.data)).toBe("—");
  });

  it("다음 페이지 판단 — 총수가 있으면 총수로, 없으면 페이지가 꽉 찼는지로", () => {
    expect(hasNextPage(0, 20, 20, 20)).toBe(false);
    expect(hasNextPage(0, 20, 20, 21)).toBe(true);
    expect(hasNextPage(1, 20, 1, 21)).toBe(false);
    expect(hasNextPage(0, 20, 20, undefined)).toBe(true);
    expect(hasNextPage(0, 20, 7, undefined)).toBe(false);
  });
});
