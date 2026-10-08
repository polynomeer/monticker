import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import GroupDeleteConfirm from "@/components/watchlist/GroupDeleteConfirm";

describe("GroupDeleteConfirm", () => {
  it("삭제할 그룹·종목 수와 Watch Rule이 꺼진다는 안내를 보여준다", () => {
    render(<GroupDeleteConfirm groupName="반도체" itemCount={4} activeRuleCount={2} onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.getByRole("alertdialog")).toHaveAccessibleName("‘반도체’ 그룹을 삭제할까요?");
    expect(screen.getByText(/담긴 종목 4개도 함께 지워지며/)).toBeInTheDocument();
    expect(screen.getByText(/Watch Rule은 자동으로 꺼집니다/)).toHaveTextContent("지금 켜져 있는 규칙 2개");
    expect(screen.getByText(/규칙과 발동 기록은 남습니다/)).toBeInTheDocument();
  });

  it("켜진 규칙 수를 모르거나 0이면 개수 없이 안내만 한다", () => {
    const { rerender } = render(<GroupDeleteConfirm groupName="g" itemCount={0} activeRuleCount={null} onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.getByText(/Watch Rule은 자동으로 꺼집니다/)).not.toHaveTextContent("지금 켜져 있는 규칙");
    rerender(<GroupDeleteConfirm groupName="g" itemCount={0} activeRuleCount={0} onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.getByText(/Watch Rule은 자동으로 꺼집니다/)).not.toHaveTextContent("지금 켜져 있는 규칙");
  });

  it("확인을 눌러야 삭제하고, 취소는 삭제하지 않는다", () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(<GroupDeleteConfirm groupName="g" itemCount={1} activeRuleCount={0} onConfirm={onConfirm} onCancel={onCancel} />);
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(onCancel).toHaveBeenCalledOnce();
    expect(onConfirm).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "그룹 삭제" }));
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it("삭제 중에는 버튼을 막아 두 번 보내지 않는다", () => {
    render(<GroupDeleteConfirm groupName="g" itemCount={1} activeRuleCount={0} pending onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.getByRole("button", { name: "삭제 중…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "취소" })).toBeDisabled();
  });
});
