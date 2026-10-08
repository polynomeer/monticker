"use client";

import { useEffect, useRef, useState } from "react";
import { Icon, IconBtn } from "@/components/terminal";

interface Props {
  name: string;
  onRemove: () => void;
  /** 없으면 "위로 이동"을 비활성으로 둔다(맨 위이거나 내 순서 정렬이 아님) */
  onMoveUp?: () => void;
  onMoveDown?: () => void;
  /** 이동을 막은 이유 — 비활성 항목의 title로 보인다 */
  moveHint?: string;
  disabled?: boolean;
}

const itemClass = "flex w-full items-center gap-2.5 px-3.5 py-2 text-13 hover:bg-tm-raised focus:bg-tm-raised focus:outline-none disabled:cursor-not-allowed disabled:opacity-50";

/**
 * 행 끝의 ⋯ 메뉴 — 위로/아래로 이동, 관심종목에서 제거.
 * 키보드: 열면 첫 항목에 포커스, ↑/↓·Home/End로 이동, Esc로 닫고 ⋯ 버튼으로 포커스를 돌려준다.
 */
export default function RowMenu({ name, onRemove, onMoveUp, onMoveDown, moveHint, disabled }: Props) {
  // 표가 가로 스크롤 컨테이너(overflow) 안에 있어 absolute 메뉴는 잘린다 — 버튼 위치 기준 fixed로 띄운다.
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
  const open = pos != null;
  const ref = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLElement | null>(null);

  const close = (refocus = false) => {
    setPos(null);
    if (refocus) triggerRef.current?.focus();
  };

  useEffect(() => {
    if (!open) return;
    const onClose = () => setPos(null);
    const h = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && onClose();
    document.addEventListener("mousedown", h);
    window.addEventListener("scroll", onClose, true);
    window.addEventListener("resize", onClose);
    // 첫 번째 활성 항목에 포커스
    menuRef.current?.querySelector<HTMLButtonElement>("[role=menuitem]:not(:disabled)")?.focus();
    return () => {
      document.removeEventListener("mousedown", h);
      window.removeEventListener("scroll", onClose, true);
      window.removeEventListener("resize", onClose);
    };
  }, [open]);

  const toggle = (btn: HTMLElement) => {
    triggerRef.current = btn;
    if (open) return setPos(null);
    const r = btn.getBoundingClientRect();
    setPos({ top: r.bottom + 4, left: Math.max(8, r.right - 176) });
  };

  const onMenuKeyDown = (e: React.KeyboardEvent) => {
    const items = Array.from(menuRef.current?.querySelectorAll<HTMLButtonElement>("[role=menuitem]:not(:disabled)") ?? []);
    const i = items.indexOf(document.activeElement as HTMLButtonElement);
    const focusAt = (n: number) => items[(n + items.length) % items.length]?.focus();
    switch (e.key) {
      case "ArrowDown": e.preventDefault(); focusAt(i + 1); break;
      case "ArrowUp": e.preventDefault(); focusAt(i - 1); break;
      case "Home": e.preventDefault(); focusAt(0); break;
      case "End": e.preventDefault(); focusAt(items.length - 1); break;
      case "Escape": e.preventDefault(); close(true); break;
      case "Tab": close(); break;
    }
  };

  const run = (fn?: () => void) => () => { close(true); fn?.(); };

  return (
    <div className="inline-block" ref={ref} onClick={(e) => e.stopPropagation()}>
      <IconBtn name="dots" label={`${name} 더 보기`} size={26} aria-haspopup="menu" aria-expanded={open} onClick={(e) => toggle(e.currentTarget)} />
      {open && (
        <div
          ref={menuRef}
          role="menu"
          aria-label={`${name} 메뉴`}
          onKeyDown={onMenuKeyDown}
          style={{ top: pos.top, left: pos.left }}
          className="fixed z-50 w-44 overflow-hidden rounded-[10px] border border-tm-line2 bg-tm-panel py-1 text-left"
        >
          <button
            type="button"
            role="menuitem"
            disabled={disabled || !onMoveUp}
            title={!onMoveUp ? moveHint : undefined}
            onClick={run(onMoveUp)}
            aria-label={`${name} 위로 이동`}
            className={`${itemClass} text-dracula-fg`}
          >
            <Icon name="chev" size={15} className="rotate-180" aria-hidden />
            위로 이동
          </button>
          <button
            type="button"
            role="menuitem"
            disabled={disabled || !onMoveDown}
            title={!onMoveDown ? moveHint : undefined}
            onClick={run(onMoveDown)}
            aria-label={`${name} 아래로 이동`}
            className={`${itemClass} text-dracula-fg`}
          >
            <Icon name="chev" size={15} aria-hidden />
            아래로 이동
          </button>
          <button
            type="button"
            role="menuitem"
            disabled={disabled}
            onClick={run(onRemove)}
            aria-label={`${name} 관심종목에서 제거`}
            className={`${itemClass} text-[#ff8a8a]`}
          >
            <Icon name="trash" size={15} />
            관심종목에서 제거
          </button>
        </div>
      )}
    </div>
  );
}
