import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

const authFetch = vi.fn();
vi.mock("@/services/api", () => ({ authFetch: (...a: unknown[]) => authFetch(...a) }));

import { RISK_PREVIEW_DEBOUNCE_MS, isPreviewable, useRiskPreview, type RiskPreviewInput } from "@/hooks/useRiskPreview";

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
