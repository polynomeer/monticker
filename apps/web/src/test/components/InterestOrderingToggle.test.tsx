import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

let token: string | null = "tok";
vi.mock("@/services/auth", () => ({ getAccessToken: () => token }));
vi.mock("@/services/api", () => ({
  authFetch: (input: string, init?: RequestInit) => fetch(input, init),
}));

import SectorHeatmap from "@/components/home/SectorHeatmap";

const row = (sector: string, stockCount: number, avgChangeRate: number) => ({
  sector, stockCount, pricedCount: stockCount, avgChangeRate, advancers: 0, decliners: 0, unchanged: 0, eventCount: 0,
});

// 종목 수 많은 순: 전기전자(30) → 음식료품(20) → 금융업(10) → 의약품(5)
const SECTORS = [row("전기전자", 30, 1), row("음식료품", 20, -1), row("금융업", 10, 0.5), row("의약품", 5, 2)];

let prefs: { interestSectors: string[]; usageStyle: string | null; updatedAt: string | null; interestOrdering: boolean };
const patchBodies: unknown[] = [];

function json(body: unknown, status = 200) {
  return Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(body) } as Response);
}

beforeEach(() => {
  token = "tok";
  patchBodies.length = 0;
  prefs = { interestSectors: ["BIO", "FINANCE"], usageStyle: null, updatedAt: "2026-10-01T00:00:00Z", interestOrdering: true };
  global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.startsWith("/api/screener/sectors/performance")) {
      return json({ market: "all", weighting: "EQUAL", eventsSince: "", sectors: SECTORS, updatedAt: "" });
    }
    if (url === "/api/users/me/preferences" && init?.method === "PATCH") {
      const body = JSON.parse(String(init.body));
      patchBodies.push(body);
      prefs = { ...prefs, interestOrdering: body.interestOrdering };
      return json(prefs);
    }
    if (url === "/api/users/me/preferences") return json(prefs);
    return json({}, 404);
  }) as typeof fetch;
});

function renderHeatmap() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <SectorHeatmap />
    </QueryClientProvider>,
  );
}

/** 타일의 섹터 이름을 화면 순서대로 */
async function tileOrder() {
  await screen.findByText("전기전자");
  return Array.from(document.querySelectorAll("[title]"))
    .map((el) => el.getAttribute("title") ?? "")
    .filter((t) => t.includes("상승"))
    .map((t) => t.split(" · ")[0]);
}

describe("SectorHeatmap — 관심 분야 순 (ADR-099)", () => {
  it("puts interest sectors first and marks them, without dropping any sector", async () => {
    renderHeatmap();
    const toggle = await screen.findByRole("button", { name: /관심 분야 순/ });
    expect(toggle).toHaveAttribute("aria-pressed", "true");
    await waitFor(async () => expect(await tileOrder()).toEqual(["금융업", "의약품", "전기전자", "음식료품"]));
    expect(screen.getAllByText("관심")).toHaveLength(2);
  });

  it("turning the toggle off restores the default order, saves the switch and keeps every sector", async () => {
    renderHeatmap();
    const toggle = await screen.findByRole("button", { name: /관심 분야 순/ });
    await waitFor(async () => expect((await tileOrder())[0]).toBe("금융업"));

    await userEvent.click(toggle);

    await waitFor(async () => expect(await tileOrder()).toEqual(["전기전자", "음식료품", "금융업", "의약품"]));
    expect(screen.getByRole("button", { name: /관심 분야 순/ })).toHaveAttribute("aria-pressed", "false");
    expect(screen.queryByText("관심")).not.toBeInTheDocument();
    await waitFor(() => expect(patchBodies).toEqual([{ interestOrdering: false }]));
  });

  it("starts off when the stored switch is off", async () => {
    prefs.interestOrdering = false;
    renderHeatmap();
    const toggle = await screen.findByRole("button", { name: /관심 분야 순/ });
    await waitFor(() => expect(toggle).toHaveAttribute("aria-pressed", "false"));
    expect(await tileOrder()).toEqual(["전기전자", "음식료품", "금융업", "의약품"]);
  });

  it("without interests: no toggle and the default order", async () => {
    prefs.interestSectors = [];
    renderHeatmap();
    expect(await tileOrder()).toEqual(["전기전자", "음식료품", "금융업", "의약품"]);
    await waitFor(() =>
      expect((global.fetch as ReturnType<typeof vi.fn>).mock.calls.some((c) => c[0] === "/api/users/me/preferences")).toBe(true),
    );
    expect(screen.queryByRole("button", { name: /관심 분야 순/ })).not.toBeInTheDocument();
  });

  it("logged out: preferences are not requested and nothing changes", async () => {
    token = null;
    renderHeatmap();
    expect(await tileOrder()).toEqual(["전기전자", "음식료품", "금융업", "의약품"]);
    expect(screen.queryByRole("button", { name: /관심 분야 순/ })).not.toBeInTheDocument();
    const calls = (global.fetch as ReturnType<typeof vi.fn>).mock.calls.map((c) => String(c[0]));
    expect(calls.some((u) => u.includes("/preferences"))).toBe(false);
    // 패널 안 섹터 타일 4개가 모두 그려진다
    expect(within(document.body).getAllByText(/%/).length).toBeGreaterThanOrEqual(4);
  });
});
