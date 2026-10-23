"use client";

import { useEffect, type ReactNode } from "react";
import { IconBtn } from "@/components/terminal";

/** 화면 가운데 대화상자 — TradeModal과 같은 모양. 배경을 누르거나 Esc를 누르면 닫힌다 */
export function Modal({ title, onClose, children, wide = false }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [onClose]);
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <section
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={`mx-4 flex max-h-[90vh] w-full flex-col rounded-[10px] border border-tm-line2 bg-tm-panel text-13 text-dracula-fg shadow-glow-line ${wide ? "max-w-3xl" : "max-w-md"}`}
      >
        <div className="flex items-center gap-1.5 border-b border-tm-line px-3 py-1.5">
          <h2 className="m-0 text-sm font-semibold">{title}</h2>
          <IconBtn name="x" label="닫기" size={28} iconSize={15} className="ml-auto" onClick={onClose} />
        </div>
        <div className="flex flex-col gap-3 overflow-y-auto p-3.5">{children}</div>
      </section>
    </div>
  );
}
