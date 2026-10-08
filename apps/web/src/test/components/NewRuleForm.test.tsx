import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import NewRuleForm from "@/components/alerts/NewRuleForm";

const mockFetch = vi.fn();
global.fetch = mockFetch;

const samsung = { stockId: 7, symbol: "005930", name: "삼성전자", market: "KOSPI" };

function respond(status: number, data: unknown) {
  return Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(data) } as Response);
}

/** 종목 검색(스크리너)은 삼성전자 하나를 돌려주고, 규칙 생성은 [createStatus]로 답한다 */
function routeFetch(createStatus: number, createBody: unknown = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if (String(url).includes("/api/alerts/rules") && init?.method === "POST") return respond(createStatus, createBody);
    if (String(url).includes("/api/screener")) return respond(200, { items: [samsung], total: 1 });
    return respond(200, []);
  });
}

function renderForm() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={qc}><NewRuleForm /></QueryClientProvider>);
}

async function pickSamsung(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("textbox", { name: "종목 선택" }));
  await user.click(await screen.findByRole("button", { name: /삼성전자/ }, { timeout: 2000 }));
}

beforeEach(() => {
  mockFetch.mockReset();
  localStorage.clear();
});

describe("NewRuleForm", () => {
  it("shows field errors and does not call the API when the form is incomplete", async () => {
    routeFetch(200);
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByRole("button", { name: "알림 규칙 만들기" }));

    expect(await screen.findByText("종목을 고르세요.")).toBeInTheDocument();
    expect(screen.getByText("기준 가격을 입력하세요.")).toBeInTheDocument();
    expect(mockFetch.mock.calls.some(([u, i]) => String(u).includes("/api/alerts/rules") && i?.method === "POST")).toBe(false);
  });

  it("posts the rule built from the form", async () => {
    routeFetch(200, { id: 1 });
    const user = userEvent.setup();
    renderForm();

    await pickSamsung(user);
    await user.type(screen.getByRole("spinbutton", { name: /기준 가격/ }), "71000");
    await user.click(screen.getByRole("button", { name: "알림 규칙 만들기" }));

    await waitFor(() => {
      const post = mockFetch.mock.calls.find(([u, i]) => String(u).includes("/api/alerts/rules") && i?.method === "POST");
      expect(post).toBeTruthy();
      expect(JSON.parse(post![1].body)).toEqual({ stockId: 7, ruleType: "PRICE_ABOVE", condition: { threshold: 71000 } });
    });
  });

  it("shows the rate-limit error from the server", async () => {
    routeFetch(429, { message: "요청이 너무 많습니다." });
    const user = userEvent.setup();
    renderForm();

    await pickSamsung(user);
    await user.selectOptions(screen.getByRole("combobox"), "VOLUME_SURGE");
    await user.click(screen.getByRole("button", { name: "알림 규칙 만들기" }));

    expect(await screen.findByText(/1시간에 20개까지/)).toBeInTheDocument();
  });
});
