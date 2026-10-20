import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ImportPreview } from "@monticker/types";

const previewAsync = vi.fn();
const commitAsync = vi.fn();
const idle = { isPending: false, isError: false, error: null, reset: vi.fn() };
vi.mock("@/hooks/usePortfolio", () => ({
  usePreviewImport: () => ({ ...idle, mutateAsync: previewAsync }),
  useCommitImport: () => ({ ...idle, mutateAsync: commitAsync }),
}));
vi.mock("@/services/portfolio", () => ({ downloadImportTemplate: vi.fn() }));

import { ImportPanel } from "@/components/assets/ImportPanel";

const row = (line: number, status: "NEW" | "ERROR", message: string | null = null) => ({
  line, status, type: status === "NEW" ? "BUY" as const : null, tradeDate: "2026-10-01", symbol: "005930", name: "삼성전자", stockId: 1,
  quantity: 1, price: 70000, amount: 70000, fee: 0, tax: 0, currency: "KRW", message,
});
const preview = (errors: number): ImportPreview => ({
  fileSha256: "sha-1", format: "MONTICKER_V1", charset: "CP949", rowCount: 2 + errors, newCount: 2, duplicateCount: 0, errorCount: errors,
  unmatchedCount: 0, rows: [row(2, "NEW"), row(3, "NEW"), ...Array.from({ length: errors }, (_, i) => row(4 + i, "ERROR", "알 수 없는 거래 구분: 환전"))],
});

async function choose(name = "t.csv", size = 10) {
  const file = new File(["x".repeat(size)], name, { type: "text/csv" });
  await userEvent.upload(screen.getByLabelText("거래내역 파일"), file);
  return file;
}

describe("CSV 가져오기", () => {
  beforeEach(() => {
    previewAsync.mockReset();
    commitAsync.mockReset().mockResolvedValue({ batchId: 9, imported: 2, duplicates: 0, skipped: 1 });
  });

  it("오류 행이 있으면 '빼고 가져오기'를 고르기 전까지 확정할 수 없고, 확정은 미리보기 해시를 보낸다", async () => {
    previewAsync.mockResolvedValue(preview(1));
    render(<ImportPanel accountId={3} />);
    const file = await choose();
    await userEvent.click(screen.getByRole("button", { name: "미리보기" }));

    expect(await screen.findByText(/읽지 못한 행이 1개/)).toBeInTheDocument();
    expect(screen.getByText("알 수 없는 거래 구분: 환전")).toBeInTheDocument();
    const commit = screen.getByRole("button", { name: "2건 가져오기" });
    expect(commit).toBeDisabled();

    await userEvent.click(screen.getByRole("checkbox", { name: "오류 1행을 빼고 가져오기" }));
    await userEvent.click(commit);

    expect(commitAsync).toHaveBeenCalledWith({ file, options: { format: "MONTICKER_V1", defaultCurrency: undefined }, skipErrors: true, sha256: "sha-1" });
    expect(await screen.findByText(/2건을 가져왔습니다/)).toBeInTheDocument();
    expect(screen.getByText(/오류 1행은 뺐습니다/)).toBeInTheDocument();
  });

  it("오류가 없으면 바로 확정할 수 있다", async () => {
    previewAsync.mockResolvedValue(preview(0));
    render(<ImportPanel accountId={3} />);
    await choose();
    await userEvent.click(screen.getByRole("button", { name: "미리보기" }));

    await userEvent.click(await screen.findByRole("button", { name: "2건 가져오기" }));
    expect(commitAsync).toHaveBeenCalledWith(expect.objectContaining({ skipErrors: false }));
  });

  it("5MB를 넘는 파일은 보내지 않는다", async () => {
    render(<ImportPanel accountId={3} />);
    await choose("big.csv", 5 * 1024 * 1024 + 1);

    expect(screen.getByText("파일은 5MB까지 올릴 수 있습니다.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "미리보기" })).toBeDisabled();
  });

  it("열 직접 지정은 필수 열을 적어야 미리볼 수 있다", async () => {
    render(<ImportPanel accountId={3} />);
    await userEvent.click(screen.getByRole("button", { name: "열 직접 지정" }));
    await choose();

    expect(screen.getByText(/필수 열 이름을 적어 주세요: 거래일, 거래 구분/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "미리보기" })).toBeDisabled();

    await userEvent.type(screen.getByLabelText("거래일 (필수)"), "체결일");
    await userEvent.type(screen.getByLabelText("거래 구분 (필수)"), "매매구분");
    expect(screen.getByRole("button", { name: "미리보기" })).toBeEnabled();
  });
});
