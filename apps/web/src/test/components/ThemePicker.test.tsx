import { afterEach, describe, expect, it } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { ThemePicker } from "@/components/settings/ThemePicker";
import { AppThemeSync } from "@/components/ThemeProvider";
import { useThemeStore } from "@/stores/themeStore";

/** 설정 화면처럼 스토어에 묶은 피커 + data-theme 동기화 */
function Harness() {
  const appTheme = useThemeStore((s) => s.appTheme);
  const setAppTheme = useThemeStore((s) => s.setAppTheme);
  return (
    <>
      <AppThemeSync />
      <ThemePicker value={appTheme} onChange={setAppTheme} />
    </>
  );
}

afterEach(() => {
  act(() => useThemeStore.setState({ appTheme: "dracula" }));
  document.documentElement.removeAttribute("data-theme");
});

describe("ThemePicker", () => {
  it("6개 카드의 라디오 그룹이고 기본은 드라큘라가 선택돼 있다", () => {
    render(<Harness />);
    expect(screen.getByRole("radiogroup", { name: "테마" })).toBeInTheDocument();
    const radios = screen.getAllByRole("radio");
    expect(radios).toHaveLength(6);
    const checked = radios.filter((r) => r.getAttribute("aria-checked") === "true");
    expect(checked).toHaveLength(1);
    expect(checked[0]).toHaveTextContent("드라큘라");
    expect(checked[0]).toHaveAttribute("tabindex", "0");
  });

  it("카드를 고르면 html[data-theme]이 바뀐다", () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole("radio", { name: /클린 라이트/ }));
    expect(useThemeStore.getState().appTheme).toBe("light");
    expect(document.documentElement.dataset.theme).toBe("light");
    expect(screen.getByRole("radio", { name: /클린 라이트/ })).toHaveAttribute("aria-checked", "true");
  });

  it("화살표 키로 옆 테마를 고르고 초점을 옮긴다", () => {
    render(<Harness />);
    const first = screen.getByRole("radio", { name: /드라큘라/ });
    first.focus();
    fireEvent.keyDown(first, { key: "ArrowRight" });
    expect(document.documentElement.dataset.theme).toBe("graphite");
    expect(screen.getByRole("radio", { name: /그래파이트/ })).toHaveFocus();
    fireEvent.keyDown(screen.getByRole("radio", { name: /그래파이트/ }), { key: "ArrowLeft" });
    fireEvent.keyDown(screen.getByRole("radio", { name: /드라큘라/ }), { key: "ArrowLeft" });
    expect(document.documentElement.dataset.theme).toBe("contrast");
  });
});
