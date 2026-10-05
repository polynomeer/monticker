import type { ReactNode } from "react";
import { BtnLink, Icon, type IconName } from "@/components/terminal";
import { cn } from "@/lib/utils";

/** 로그인 전 화면 — 패널 하나에 이유와 로그인 버튼만 둔다. */
export function LoginRequired({ message, icon = "lock" }: { message: ReactNode; icon?: IconName }) {
  return (
    <section className="flex flex-col items-center gap-4 rounded-[10px] bg-tm-panel px-6 py-16 text-center">
      <span className="grid h-12 w-12 place-items-center rounded-full bg-tm-raised text-dracula-purple">
        <Icon name={icon} size={22} />
      </span>
      <p className="m-0 text-sm text-tm-soft">{message}</p>
      <BtnLink href="/login" kind="primary">로그인</BtnLink>
    </section>
  );
}

/** 불러오는 중 자리표시 — 패널 안쪽 높이만큼 은은하게 깜빡인다. */
export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden className={cn("animate-pulse rounded-lg bg-tm-inner", className)} />;
}

/** 패널 안의 빈 상태 문구 */
export function EmptyNote({ children, className }: { children: ReactNode; className?: string }) {
  return <p className={cn("m-0 py-8 text-center text-13 text-tm-muted", className)}>{children}</p>;
}
