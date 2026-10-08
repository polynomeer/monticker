import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import RowMenu from "@/components/watchlist/RowMenu";

describe("watchlist RowMenu", () => {
  it("focuses the first enabled item and moves focus with the arrow keys", async () => {
    const user = userEvent.setup();
    const onMoveDown = vi.fn();
    render(<RowMenu name="삼성전자" onRemove={vi.fn()} onMoveDown={onMoveDown} moveHint="맨 위" />);

    await user.click(screen.getByRole("button", { name: "삼성전자 더 보기" }));

    // 맨 위라 "위로 이동"은 비활성 — 포커스는 "아래로 이동"에서 시작
    expect(screen.getByRole("menuitem", { name: "삼성전자 위로 이동" })).toBeDisabled();
    expect(screen.getByRole("menuitem", { name: "삼성전자 아래로 이동" })).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitem", { name: "삼성전자 관심종목에서 제거" })).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitem", { name: "삼성전자 아래로 이동" })).toHaveFocus();

    await user.keyboard("{Enter}");
    expect(onMoveDown).toHaveBeenCalledOnce();
    expect(screen.queryByRole("menu")).toBeNull();
    expect(screen.getByRole("button", { name: "삼성전자 더 보기" })).toHaveFocus();
  });

  it("closes on Escape and returns focus to the trigger", async () => {
    const user = userEvent.setup();
    render(<RowMenu name="카카오" onRemove={vi.fn()} onMoveUp={vi.fn()} onMoveDown={vi.fn()} />);

    await user.click(screen.getByRole("button", { name: "카카오 더 보기" }));
    expect(screen.getByRole("menuitem", { name: "카카오 위로 이동" })).toHaveFocus();
    await user.keyboard("{Escape}");

    expect(screen.queryByRole("menu")).toBeNull();
    expect(screen.getByRole("button", { name: "카카오 더 보기" })).toHaveFocus();
  });

  it("no longer shows a 준비 중 placeholder", async () => {
    const user = userEvent.setup();
    render(<RowMenu name="NAVER" onRemove={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "NAVER 더 보기" }));
    expect(screen.queryByText(/준비 중/)).toBeNull();
  });
});
