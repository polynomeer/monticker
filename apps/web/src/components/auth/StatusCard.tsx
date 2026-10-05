import type { ReactNode } from "react";
import { Icon, type IconName } from "@/components/terminal/Icon";
import { cn } from "@/lib/utils";
import { BrandLink } from "./AuthShell";

/**
 * 앱 골격 바깥의 가운데 정렬 화면 — 로고 + 카드. 비밀번호 찾기/재설정, 결제·OAuth 콜백 등.
 * 시안 p_account.reset()의 카드(패널 배경, 둥근 16px, 32px 패딩).
 */
export function CenteredPage({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col items-center gap-8 bg-tm-page px-6 py-12 text-sm text-dracula-fg">
      <BrandLink />
      <div className="flex w-full justify-center">{children}</div>
    </div>
  );
}

export function StatusCardFrame({ children, className, live }: { children: ReactNode; className?: string; live?: boolean }) {
  return (
    <section
      aria-live={live ? "polite" : undefined}
      className={cn("flex w-full min-w-0 max-w-[440px] flex-col gap-[18px] rounded-2xl bg-tm-panel p-6 sm:p-8", className)}
    >
      {children}
    </section>
  );
}

const TONE = {
  ok: { bg: "bg-[#22392c]", fg: "text-dracula-green", icon: "check" as IconName },
  error: { bg: "bg-[#47262d]", fg: "text-[#ff8a8a]", icon: "x" as IconName },
  warn: { bg: "bg-[#43342a]", fg: "text-dracula-orange", icon: "alert" as IconName },
  pending: { bg: "bg-tm-raised", fg: "text-dracula-purple", icon: "clock" as IconName },
};

/** 원형 상태 아이콘(52px) — 완료/실패/진행 중 */
export function StatusIcon({ tone }: { tone: keyof typeof TONE }) {
  const t = TONE[tone];
  return (
    <span className={cn("grid h-[52px] w-[52px] place-items-center rounded-full", t.bg, t.fg)}>
      {tone === "pending" ? (
        <span className="h-6 w-6 animate-spin rounded-full border-[3px] border-current border-t-transparent" aria-hidden />
      ) : (
        <Icon name={t.icon} size={26} strokeWidth={2.6} />
      )}
    </span>
  );
}

/** 상태 카드 — 아이콘 · 제목 · 본문 · 동작 */
export function StatusCard({
  tone, step, title, children, actions,
}: {
  tone: keyof typeof TONE;
  /** 카드 맨 위 작은 라벨 */
  step?: ReactNode;
  title: ReactNode;
  children?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <StatusCardFrame live>
      {step && <span className="text-xs text-tm-muted">{step}</span>}
      <StatusIcon tone={tone} />
      <h1 className="m-0 text-[1.375rem] font-bold">{title}</h1>
      {children && <div className="flex flex-col gap-2 leading-relaxed text-tm-soft">{children}</div>}
      {actions && <div className="flex flex-col gap-2">{actions}</div>}
    </StatusCardFrame>
  );
}
