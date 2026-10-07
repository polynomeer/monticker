"use client";

import { Icon, type IconName } from "@/components/terminal/Icon";
import { useToastStore, Toast, ToastType } from "@/hooks/useToast";

// 터미널 Notice 톤과 같은 색 — 성공/실패는 상승·하락색이 아니라 의미색(초록/빨강)을 쓴다.
// (사용자가 한국식 시세 색을 고르면 상승색이 빨강이 되어 "성공=빨강"이 되는 걸 막는다)
const STYLES: Record<ToastType, { box: string; icon: IconName; tone: string }> = {
  success: { box: "border-[#2f4d39]", icon: "check", tone: "text-dracula-green" },
  error: { box: "border-[#64363f]", icon: "x", tone: "text-[#ff8a8a]" },
  warning: { box: "border-[#5a4430]", icon: "alert", tone: "text-dracula-orange" },
  info: { box: "border-[#33415e]", icon: "info", tone: "text-dracula-cyan" },
};

function ToastItem({ toast }: { toast: Toast }) {
  const removeToast = useToastStore((s) => s.removeToast);
  const s = STYLES[toast.type];

  return (
    <div
      className={`flex min-w-[280px] max-w-sm items-start gap-2.5 rounded-[10px] border bg-tm-panel px-3.5 py-3 shadow-[0_8px_32px_rgba(0,0,0,0.45)] animate-pop-in ${s.box}`}
    >
      <Icon name={s.icon} size={16} strokeWidth={2.2} className={`mt-0.5 flex-none ${s.tone}`} />
      <div className="min-w-0 flex-1">
        <p className="m-0 text-13 font-semibold text-dracula-fg">{toast.title}</p>
        {toast.message && <p className="m-0 mt-0.5 text-xs leading-relaxed text-tm-soft">{toast.message}</p>}
      </div>
      <button
        type="button"
        onClick={() => removeToast(toast.id)}
        className="-mr-1 -mt-0.5 ml-1 grid h-6 w-6 flex-none place-items-center rounded-md text-tm-muted hover:bg-tm-raised hover:text-dracula-fg"
        aria-label="닫기"
      >
        <Icon name="x" size={14} />
      </button>
    </div>
  );
}

export function ToastContainer() {
  const toasts = useToastStore((s) => s.toasts);
  const errors   = toasts.filter(t => t.type === "error");
  const nonErrors = toasts.filter(t => t.type !== "error");

  return (
    <div className="pointer-events-none fixed bottom-6 left-4 right-4 z-50 flex flex-col items-end gap-2 sm:left-auto sm:right-6">
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
