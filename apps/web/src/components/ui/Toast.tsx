"use client";

import { type Icon, CheckCircle, XCircle, Warning, Info, X } from "@phosphor-icons/react";
import { useToastStore, Toast, ToastType } from "@/hooks/useToast";

const STYLES: Record<
  ToastType,
  { bar: string; icon: Icon; title: string; msg: string }
> = {
  success: {
    bar: "border-l-4 border-market-up bg-[#1a2e26]",
    icon: CheckCircle,
    title: "text-market-up",
    msg: "text-[#a0c4b0]",
  },
  error: {
    bar: "border-l-4 border-market-down bg-[#2e1a1e]",
    icon: XCircle,
    title: "text-market-down",
    msg: "text-[#c4a0a6]",
  },
  warning: {
    bar: "border-l-4 border-dracula-yellow bg-[#2a2a1a]",
    icon: Warning,
    title: "text-dracula-yellow",
    msg: "text-[#c4c4a0]",
  },
  info: {
    bar: "border-l-4 border-dracula-cyan bg-[#1a2a2e]",
    icon: Info,
    title: "text-dracula-cyan",
    msg: "text-[#a0b4c4]",
  },
};

function ToastItem({ toast }: { toast: Toast }) {
  const removeToast = useToastStore((s) => s.removeToast);
  const s = STYLES[toast.type];

  return (
    <div
      className={`flex items-start gap-3 px-4 py-3 rounded-lg shadow-lg backdrop-blur-sm min-w-[280px] max-w-sm ${s.bar} animate-slide-in`}
    >
      <s.icon size={20} weight="bold" className={`shrink-0 mt-0.5 ${s.title}`} aria-hidden />
      <div className="flex-1 min-w-0">
        <p className={`font-semibold text-sm ${s.title}`}>{toast.title}</p>
        {toast.message && (
          <p className={`text-xs mt-0.5 ${s.msg}`}>{toast.message}</p>
        )}
      </div>
      <button
        onClick={() => removeToast(toast.id)}
        className="inline-flex items-center justify-center w-6 h-6 -mr-1 -mt-1 text-dracula-comment hover:text-dracula-fg ml-1 transition-colors active:scale-90"
        aria-label="닫기"
      >
        <X size={14} weight="bold" aria-hidden />
      </button>
    </div>
  );
}

export function ToastContainer() {
  const toasts = useToastStore((s) => s.toasts);
  const errors   = toasts.filter(t => t.type === "error");
  const nonErrors = toasts.filter(t => t.type !== "error");

  return (
    <div className="fixed bottom-6 right-6 z-50 flex flex-col gap-2 pointer-events-none">
      {/* 에러 토스트 — assertive (즉시 읽기) */}
      <div aria-live="assertive" aria-atomic="true" className="contents">
        {errors.map(t => (
          <div key={t.id} className="pointer-events-auto">
            <ToastItem toast={t} />
          </div>
        ))}
      </div>
      {/* 일반 토스트 — polite (현재 읽기 완료 후) */}
      <div aria-live="polite" aria-atomic="false" className="contents">
        {nonErrors.map(t => (
          <div key={t.id} className="pointer-events-auto">
            <ToastItem toast={t} />
          </div>
        ))}
      </div>
    </div>
  );
}
