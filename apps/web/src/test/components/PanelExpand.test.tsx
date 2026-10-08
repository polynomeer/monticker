import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { Panel } from "@/components/terminal/Panel";

function Counter() {
  const [n, setN] = useState(0);
  return (
    <button type="button" onClick={() => setN((v) => v + 1)}>
      눌림 {n}
    </button>
  );
}

function Fixture() {
  return (
    <>
      <button type="button">바깥 버튼</button>
      <Panel tabs={["호가"]} actions={["sliders", "expand"]}>
        <Counter />
        <input aria-label="수량" />
      </Panel>
    </>
  );
}

describe("Panel 확대", () => {
  it("확대 버튼을 누르면 접근 가능한 이름이 있는 모달로 열리고 닫기 버튼에 포커스가 간다", async () => {
    render(<Fixture />);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "패널 확대" }));

    const dialog = screen.getByRole("dialog", { name: "호가 확대" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(screen.getByRole("button", { name: "확대 닫기" })).toHaveFocus();
    expect(document.body.style.overflow).toBe("hidden");
  });

  it("Esc로 닫고 확대 버튼으로 포커스를 돌려준다", async () => {
    render(<Fixture />);
    const expand = screen.getByRole("button", { name: "패널 확대" });
    await userEvent.click(expand);
    expect(screen.getByRole("dialog")).toBeInTheDocument();

    await userEvent.keyboard("{Escape}");

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "패널 확대" })).toHaveFocus();
    expect(document.body.style.overflow).toBe("");
  });

  it("닫기 버튼으로도 닫힌다", async () => {
    render(<Fixture />);
    await userEvent.click(screen.getByRole("button", { name: "패널 확대" }));
    await userEvent.click(screen.getByRole("button", { name: "확대 닫기" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("열려 있는 동안 Tab 포커스가 패널 밖으로 나가지 않는다", async () => {
    render(<Fixture />);
    await userEvent.click(screen.getByRole("button", { name: "패널 확대" }));
    const close = screen.getByRole("button", { name: "확대 닫기" });
    const input = screen.getByRole("textbox", { name: "수량" });

    // 마지막 요소에서 Tab → 처음으로
    input.focus();
    await userEvent.tab();
    expect(screen.getByRole("dialog")).toContainElement(document.activeElement as HTMLElement);
    expect(document.activeElement).not.toBe(screen.getByRole("button", { name: "바깥 버튼" }));

    // 첫 요소에서 Shift+Tab → 마지막으로
    const dialog = screen.getByRole("dialog");
    const first = dialog.querySelector<HTMLElement>("button:not([aria-disabled='true'])")!;
    first.focus();
    await userEvent.tab({ shift: true });
    expect(input).toHaveFocus();
    expect(close).toBeInTheDocument();
  });

  it("확대해도 내용을 다시 마운트하지 않아 상태가 유지된다", async () => {
    render(<Fixture />);
    await userEvent.click(screen.getByRole("button", { name: "눌림 0" }));
    await userEvent.click(screen.getByRole("button", { name: "패널 확대" }));
    expect(screen.getByRole("button", { name: "눌림 1" })).toBeInTheDocument();
    await userEvent.keyboard("{Escape}");
    expect(screen.getByRole("button", { name: "눌림 1" })).toBeInTheDocument();
  });

  it("패널 설정 아이콘은 동작하지 않는 비활성(준비 중)으로 둔다", () => {
    render(<Fixture />);
    expect(screen.getByRole("button", { name: "패널 설정 (준비 중)" })).toHaveAttribute("aria-disabled", "true");
  });
});
