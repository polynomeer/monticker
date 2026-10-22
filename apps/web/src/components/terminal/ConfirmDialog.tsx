"use client";

import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from "react";
import { Btn } from "./ui";
import { Icon } from "./Icon";

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/**
 * 되돌리기 어려운 동작 전 확인 창 — role="dialog" aria-modal, Esc·배경 클릭으로 취소, Tab은 창 안에서만 돈다(패널 확대와 같은 방식).
 * 처음 포커스는 취소 버튼에 둔다 — Enter를 눌러 버릇처럼 확정하지 않게. 닫히면 연 요소로 포커스를 돌려준다.
 */
export function ConfirmDialog({
  title, children, confirmLabel, cancelLabel = "취소", onConfirm, onCancel, pending, confirmKind = "primary",
}: {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
  /** 확정 처리 중 — 두 버튼을 막고 Esc로 닫히지 않게 한다 */
  pending?: boolean;
  confirmKind?: "primary" | "danger" | "buy" | "sell" | "live";
}) {
  const titleId = useId();
  const boxRef = useRef<HTMLElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  // 열릴 때 취소 버튼으로, 닫힐 때 연 요소로 포커스를 옮긴다.
  useEffect(() => {
    const back = document.activeElement as HTMLElement | null;
    cancelRef.current?.focus();
    return () => {
      if (back && document.contains(back)) back.focus();
    };
  }, []);

  // 포커스가 창 밖(body)으로 빠진 경우에도 Esc로 닫히게 문서 단위로 듣는다. 처리 중에는 닫지 않는다.
  useEffect(() => {
    if (pending) return;
    const onEsc = (e: globalThis.KeyboardEvent) => { if (e.key === "Escape") onCancel(); };
    document.addEventListener("keydown", onEsc);
    return () => document.removeEventListener("keydown", onEsc);
  }, [onCancel, pending]);

  const onKeyDown = (e: KeyboardEvent<HTMLElement>) => {
    if (e.key !== "Tab" || !boxRef.current) return;
    const items = Array.from(boxRef.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (items.length === 0) { e.preventDefault(); return; }
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    if (e.shiftKey && (active === first || !boxRef.current.contains(active))) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && (active === last || !boxRef.current.contains(active))) {
      e.preventDefault();
      first.focus();
    }
  };

  return (
    <div className="fixed inset-0 z-70 flex items-center justify-center bg-black/70 p-4" onClick={(e) => { if (e.target === e.currentTarget && !pending) onCancel(); }}>
      <section
        ref={boxRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={onKeyDown}
        className="flex w-full max-w-[420px] flex-col gap-3.5 rounded-xl border-[1.5px] border-live bg-tm-panel p-5 shadow-glow-line"
      >
        <div className="flex items-center gap-2.5">
          <Icon name="alert" size={20} className="flex-none text-live" />
          <h2 id={titleId} className="m-0 text-base font-bold">{title}</h2>
        </div>
        {children}
        <div className="flex gap-2">
          <Btn ref={cancelRef} kind="ghost" size="lg" className="flex-1" onClick={onCancel} disabled={pending}>{cancelLabel}</Btn>
          <Btn kind={confirmKind} size="lg" className="flex-1" onClick={onConfirm} disabled={pending}>{confirmLabel}</Btn>
        </div>
      </section>
    </div>
  );
}
