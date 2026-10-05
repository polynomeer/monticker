"use client";

import { useEffect, useRef, useState } from "react";
import { Icon, IconBtn } from "@/components/terminal";

/** 행 끝의 ⋯ 버튼 — 시안의 "순서 이동" 자리. 순서 변경 API가 없어 지금은 제거 메뉴만 연다. */
export default function RowMenu({ name, onRemove, disabled }: { name: string; onRemove: () => void; disabled?: boolean }) {
  // 표가 가로 스크롤 컨테이너(overflow) 안에 있어 absolute 메뉴는 잘린다 — 버튼 위치 기준 fixed로 띄운다.
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
  const open = pos != null;
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = () => setPos(null);
    const h = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && close();
    const k = (e: KeyboardEvent) => e.key === "Escape" && close();
    document.addEventListener("mousedown", h);
    document.addEventListener("keydown", k);
    window.addEventListener("scroll", close, true);
    window.addEventListener("resize", close);
    return () => {
      document.removeEventListener("mousedown", h);
      document.removeEventListener("keydown", k);
      window.removeEventListener("scroll", close, true);
      window.removeEventListener("resize", close);
    };
  }, [open]);
  const toggle = (btn: HTMLElement) => {
    if (open) return setPos(null);
    const r = btn.getBoundingClientRect();
    setPos({ top: r.bottom + 4, left: Math.max(8, r.right - 176) });
  };

  return (
    <div className="inline-block" ref={ref} onClick={(e) => e.stopPropagation()}>
      <IconBtn name="dots" label={`${name} 더 보기`} size={26} aria-haspopup="menu" aria-expanded={open} onClick={(e) => toggle(e.currentTarget)} />
      {open && (
        <div role="menu" style={{ top: pos.top, left: pos.left }} className="fixed z-50 w-44 overflow-hidden rounded-[10px] border border-tm-line2 bg-tm-panel py-1 text-left">
          <button
            type="button"
            role="menuitem"
            disabled={disabled}
            onClick={() => { setPos(null); onRemove(); }}
            aria-label={`${name} 관심종목에서 제거`}
            className="flex w-full items-center gap-2.5 px-3.5 py-2 text-13 text-[#ff8a8a] hover:bg-tm-raised disabled:opacity-50"
          >
            <Icon name="trash" size={15} />
            관심종목에서 제거
          </button>
          <span className="flex items-center gap-2.5 px-3.5 py-2 text-13 text-tm-muted" aria-disabled="true">
            <Icon name="dots" size={15} />
            순서 이동 (준비 중)
          </span>
        </div>
      )}
    </div>
  );
}
