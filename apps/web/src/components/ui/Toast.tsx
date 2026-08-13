"use client";

import { useToastStore, Toast, ToastType } from "@/hooks/useToast";

const STYLES: Record<
  ToastType,
  { bar: string; icon: string; title: string; msg: string }
> = {
  success: {
    bar: "border-l-4 border-[#0ecb81] bg-[#1a2e26]",
    icon: "✓",
    title: "text-[#0ecb81]",
    msg: "text-[#a0c4b0]",
  },
  error: {
    bar: "border-l-4 border-[#f6465d] bg-[#2e1a1e]",
    icon: "✕",
    title: "text-[#f6465d]",
    msg: "text-[#c4a0a6]",
  },
  warning: {
    bar: "border-l-4 border-[#f1fa8c] bg-[#2a2a1a]",
    icon: "⚠",
    title: "text-[#f1fa8c]",
    msg: "text-[#c4c4a0]",
  },
  info: {
    bar: "border-l-4 border-[#8be9fd] bg-[#1a2a2e]",
    icon: "ℹ",
    title: "text-[#8be9fd]",
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
      <span className={`text-lg font-bold mt-0.5 ${s.title}`}>{s.icon}</span>
      <div className="flex-1 min-w-0">
        <p className={`font-semibold text-sm ${s.title}`}>{toast.title}</p>
        {toast.message && (
          <p className={`text-xs mt-0.5 ${s.msg}`}>{toast.message}</p>
        )}
      </div>
      <button
        onClick={() => removeToast(toast.id)}
        className="text-[#6272a4] hover:text-[#f8f8f2] text-lg leading-none ml-1 transition-colors active:scale-90"
        aria-label="닫기"
      >
        ×
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
