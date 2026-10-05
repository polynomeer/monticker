"use client";

import { useEffect } from "react";
import { IconBtn } from "@/components/terminal";
import { ReceiptCard, type Receipt } from "./ReceiptCard";

interface Props {
  receipt: Receipt;
  onClose: () => void;
}

/** 체결 직후 뜨는 투자 영수증 모달 — 시안 Wallet의 "투자 영수증" 패널과 같은 내용. */
export default function TradeReceipt({ receipt, onClose }: Props) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/70 p-4 sm:items-center">
      <section
        role="dialog"
        aria-modal="true"
        aria-label="투자 영수증"
        className="flex max-h-[calc(100vh-2rem)] w-full max-w-[400px] flex-col overflow-y-auto rounded-[10px] border border-tm-line2 bg-tm-panel text-13 text-dracula-fg shadow-glow-line"
      >
        <div className="flex items-center gap-1.5 border-b border-tm-line px-2 py-1.5">
          <span className="inline-flex h-[30px] items-center rounded-md bg-tm-raised px-2.5 text-13 font-semibold">투자 영수증</span>
          <IconBtn name="x" label="닫기" size={28} iconSize={15} className="ml-auto" onClick={onClose} />
        </div>
        <div className="flex flex-col gap-3 p-3.5">
          <ReceiptCard receipt={receipt} onSaved={onClose} onSkip={onClose} />
        </div>
      </section>
    </div>
  );
}
