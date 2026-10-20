import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

const mutateAsync = vi.fn();
vi.mock("@/hooks/usePortfolio", () => ({
  useCreateAccount: () => ({ mutateAsync, isPending: false, isError: false, error: null }),
}));

import { AddAccountDialog } from "@/components/assets/AddAccountDialog";

function open(consentNeeded: boolean) {
  const onCreated = vi.fn();
  render(<AddAccountDialog consentNeeded={consentNeeded} consentRenewal={false} onClose={vi.fn()} onCreated={onCreated} />);
  return onCreated;
}

describe("계좌 추가", () => {
  beforeEach(() => mutateAsync.mockReset().mockResolvedValue({ id: 5 }));

  it("처음이면 동의 전문을 보여 주고, 동의해야 만들며, 동의를 같은 요청에 담는다", async () => {
    const onCreated = open(true);
    await userEvent.type(screen.getByLabelText("계좌 이름"), "키움 일반");
    const submit = screen.getByRole("button", { name: "계좌 추가" });
    expect(screen.getByText(/보유 종목의 소식/)).toBeInTheDocument();
    expect(submit).toBeDisabled();

    await userEvent.click(screen.getByRole("checkbox", { name: /계좌 정보 수집·이용에 동의합니다/ }));
    await userEvent.click(submit);

    expect(mutateAsync).toHaveBeenCalledWith(expect.objectContaining({
      name: "키움 일반", source: "CSV", consents: ["PORTFOLIO_DATA_COLLECTION"], accountRefLast4: null,
    }));
    expect(onCreated).toHaveBeenCalledWith({ id: 5 });
  });

  it("이미 동의했으면 동의를 묻지 않고 보내지도 않는다", async () => {
    open(false);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("계좌 이름"), "손 입력 계좌");
    await userEvent.click(screen.getByRole("button", { name: "손 입력" }));
    await userEvent.click(screen.getByRole("button", { name: "계좌 추가" }));

    expect(mutateAsync).toHaveBeenCalledWith(expect.objectContaining({ source: "MANUAL", consents: [] }));
  });

  it("계좌번호는 끝 4자리 숫자만 받는다", async () => {
    open(false);
    await userEvent.type(screen.getByLabelText("계좌 이름"), "a");
    await userEvent.type(screen.getByLabelText(/계좌번호 끝 4자리/), "12a");

    expect(screen.getByText("끝 4자리 숫자만 넣습니다")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "계좌 추가" })).toBeDisabled();
  });
});
