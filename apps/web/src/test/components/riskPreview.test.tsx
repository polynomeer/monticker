import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, renderHook, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

const authFetch = vi.fn();
vi.mock("@/services/api", () => ({ authFetch: (...a: unknown[]) => authFetch(...a) }));

import { RISK_PREVIEW_DEBOUNCE_MS, isPreviewable, nextAnchorPrice, useAnchoredPrice, useRiskPreview, type RiskPreviewInput } from "@/hooks/useRiskPreview";

function wrap(children: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function Probe({ input, enabled = true }: { input: RiskPreviewInput | null; enabled?: boolean }) {
  const r = useRiskPreview(input, enabled);
  return (
    <span data-testid="out">
      {r.idle ? "idle" : r.pending ? "pending" : r.error ? `error:${r.error.message}` : r.data ? `ok:${r.data.approved}` : "none"}
    </span>
  );
}

const base: RiskPreviewInput = { stockId: 1, side: "BUY", quantity: 1, estimatedPrice: 70000 };
const ok = (approved = true) => ({ ok: true, status: 200, json: async () => ({ approved, blockedBy: null, severity: "APPROVED", checks: [] }) });

/** fetch 응답·react-query 알림(내부 setTimeout)을 가짜 시계로 흘려보낸다. */
async function flush() {
  for (let i = 0; i < 3; i++) await act(async () => { await vi.advanceTimersByTimeAsync(10); });
}

describe("isPreviewable — 서버 RiskPreviewRequest와 같은 검증", () => {
  it("정수 수량·BUY/SELL·0 이상 가격만", () => {
    expect(isPreviewable(base)).toBe(true);
    expect(isPreviewable({ ...base, estimatedPrice: 0 })).toBe(true);           // 시장가
    expect(isPreviewable({ ...base, quantity: 0 })).toBe(false);
    expect(isPreviewable({ ...base, quantity: 1.5 })).toBe(false);
    expect(isPreviewable({ ...base, quantity: 1_000_001 })).toBe(false);
    expect(isPreviewable({ ...base, estimatedPrice: -1 })).toBe(false);
    expect(isPreviewable({ ...base, estimatedPrice: Number.NaN })).toBe(false);
    expect(isPreviewable({ ...base, side: "HOLD" as "BUY" })).toBe(false);
    expect(isPreviewable(null)).toBe(false);
  });
});

describe("useRiskPreview (ADR-092)", () => {
  beforeEach(() => { vi.useFakeTimers(); authFetch.mockReset(); });
  afterEach(() => { vi.useRealTimers(); });

  it("입력이 멈춘 뒤 한 번만 미리보기 엔드포인트를 부른다 — 감사되는 /api/risk/check가 아니다", async () => {
    authFetch.mockResolvedValue(ok());
    const { rerender } = render(wrap(<Probe input={base} />));
    for (const q of [2, 3, 4, 5]) {
      await act(async () => { vi.advanceTimersByTime(100); });
      rerender(wrap(<Probe input={{ ...base, quantity: q }} />));
    }
    expect(screen.getByTestId("out")).toHaveTextContent("pending");
    expect(authFetch).not.toHaveBeenCalled();

    await act(async () => { vi.advanceTimersByTime(RISK_PREVIEW_DEBOUNCE_MS); });
    await flush();

    expect(authFetch).toHaveBeenCalledTimes(1);
    const [url, init] = authFetch.mock.calls[0];
    expect(url).toBe("/api/risk/preview");
    expect(JSON.parse(init.body)).toEqual({ ...base, quantity: 5 });
    expect(screen.getByTestId("out")).toHaveTextContent("ok:true");
  });

  it("검증을 통과하지 못하거나 꺼져 있으면 부르지 않는다", async () => {
    render(wrap(<><Probe input={{ ...base, quantity: 0 }} /><Probe input={base} enabled={false} /></>));
    await act(async () => { vi.advanceTimersByTime(RISK_PREVIEW_DEBOUNCE_MS * 3); });
    await flush();
    expect(authFetch).not.toHaveBeenCalled();
    expect(screen.getAllByTestId("out").map((e) => e.textContent)).toEqual(["idle", "idle"]);
  });

  it("429면 다시 묻지 않고 안내만 한다", async () => {
    authFetch.mockResolvedValue({ ok: false, status: 429, json: async () => ({ message: "요청 한도 초과" }) });
    render(wrap(<Probe input={base} />));
    await act(async () => { vi.advanceTimersByTime(RISK_PREVIEW_DEBOUNCE_MS); });
    await flush();
    expect(screen.getByTestId("out")).toHaveTextContent("error:점검 요청이 많습니다");
    await act(async () => { vi.advanceTimersByTime(10_000); });
    expect(authFetch).toHaveBeenCalledTimes(1);
  });
});

describe("시장가 미리보기 기준가 — 틱마다 미리보기를 다시 부르지 않는다", () => {
  it("1% 이내 움직임은 기준가를 유지하고, 넘으면 실시간가로 옮긴다", () => {
    expect(nextAnchorPrice(70_000, 70_500)).toBe(70_000);   // +0.7%
    expect(nextAnchorPrice(70_000, 69_400)).toBe(70_000);   // -0.86%
    expect(nextAnchorPrice(70_000, 70_800)).toBe(70_800);   // +1.14%
    expect(nextAnchorPrice(0, 70_000)).toBe(70_000);        // 기준이 없으면 바로 잡는다
    expect(nextAnchorPrice(70_000, 0)).toBe(70_000);        // 시세가 비면 유지
  });

  it("실시간가가 매초 조금씩 바뀌어도 기준가는 그대로", () => {
    const { result, rerender } = renderHook(({ live }) => useAnchoredPrice(live, "2:BUY:MARKET:10"), {
      initialProps: { live: 70_000 },
    });
    for (const live of [70_100, 69_950, 70_200, 70_300, 69_800]) rerender({ live });
    expect(result.current).toBe(70_000);

    rerender({ live: 71_000 });
    expect(result.current).toBe(71_000);
  });

  it("사용자 입력이 바뀌면 그때의 현재가로 다시 잡는다", () => {
    const { result, rerender } = renderHook(({ live, key }) => useAnchoredPrice(live, key), {
      initialProps: { live: 70_000, key: "2:BUY:MARKET:10" },
    });
    rerender({ live: 70_300, key: "2:BUY:MARKET:10" });
    expect(result.current).toBe(70_000);

    rerender({ live: 70_300, key: "2:BUY:MARKET:20" });
    expect(result.current).toBe(70_300);
  });
});
