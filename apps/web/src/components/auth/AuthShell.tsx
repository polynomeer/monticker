import Link from "next/link";
import type { ComponentProps, ReactNode } from "react";
import { LogoMark } from "@/components/terminal/Shell";
import { Icon, type IconName } from "@/components/terminal/Icon";
import { cn } from "@/lib/utils";
import { EventChartIllustration } from "./EventChartIllustration";

/** 로고 + 워드마크 — 앱 골격(TerminalPage) 바깥 화면들의 머리 */
export function BrandLink({ size = 28, className }: { size?: number; className?: string }) {
  return (
    <Link href="/" className={cn("flex items-center gap-2.5 text-dracula-fg hover:text-dracula-fg", className)}>
      <LogoMark size={size} />
      <span className={cn("font-bold tracking-[-0.02em]", size >= 28 ? "text-[1.1875rem]" : "text-lg")}>monticker</span>
    </Link>
  );
}

/**
 * 로그인·회원가입 화면 — 왼쪽 브랜드 패널(카피 + 이벤트 차트 예시) | 오른쪽 폼.
 * 시안 p_account.auth_shell(). 좁은 화면에서는 위아래로 쌓인다.
 */
export function AuthShell({ title, children }: { title: ReactNode; children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-wrap gap-4 bg-tm-page p-4 text-sm text-dracula-fg">
      <div className="flex min-w-0 flex-[1_1_480px] flex-col justify-between gap-8 rounded-2xl bg-tm-panel p-6 sm:p-10">
        <BrandLink />
        <div className="flex flex-col gap-5">
          <p className="m-0 text-[1.625rem] font-bold leading-[1.35] tracking-[-0.02em] sm:text-[2rem]">{title}</p>
          <p className="m-0 text-15 leading-[1.7] text-tm-soft">
            뉴스·공시·거래량 이상 신호를 타임라인에 겹쳐 보고, 아이디어를 규칙으로 만들어 백테스트와 모의투자로 검증하세요.
          </p>
        </div>
        <div className="rounded-xl bg-tm-inner p-3.5">
          <EventChartIllustration />
        </div>
        <span className="text-xs text-tm-muted">monticker는 투자자문·투자중개업자가 아니며, 제공 정보는 투자 권유가 아닙니다.</span>
      </div>
      <main className="flex min-w-0 flex-[1_1_420px] items-center justify-center px-4 py-8">
        <div className="flex w-full max-w-[400px] flex-col gap-5">{children}</div>
      </main>
    </div>
  );
}

/** 폼 머리 — 큰 제목 + 보조 링크 문장 */
export function AuthHeading({ title, children }: { title: ReactNode; children?: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <h1 className="m-0 text-[1.625rem] font-bold">{title}</h1>
      {children && <span className="text-tm-muted">{children}</span>}
    </div>
  );
}

/** 가운데 구분선 + 문구("또는") */
export function OrDivider({ children }: { children: ReactNode }) {
  return (
    <div className="flex items-center gap-3 text-xs text-tm-muted">
      <span className="h-px flex-1 bg-tm-line" />
      {children}
      <span className="h-px flex-1 bg-tm-line" />
    </div>
  );
}

/**
 * 라벨이 박스 바깥 위에 붙는 넓은 입력. 터미널 키트의 TextField와 모양은 같지만,
 * 라벨을 htmlFor로 연결하고 오류를 aria-describedby로 묶어 라벨 텍스트가 오류 문구와 섞이지 않게 한다.
 */
export function AuthField({
  id, label, icon, hint, error, className, ...rest
}: { id: string; label: string; icon?: IconName; hint?: ReactNode; error?: ReactNode } & Omit<ComponentProps<"input">, "id">) {
  const descId = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div className={cn("flex flex-col gap-1.5 text-13 text-tm-soft", className)}>
      <label htmlFor={id}>{label}</label>
      <span
        className={cn(
          "flex h-[46px] items-center gap-2.5 rounded-[10px] border bg-tm-inner px-3.5 focus-within:border-dracula-purple",
          error ? "border-[#ff8a8a]" : "border-tm-line2",
        )}
      >
        {icon && <Icon name={icon} size={16} className="flex-none text-tm-muted" />}
        <input
          id={id}
          aria-invalid={!!error}
          aria-describedby={descId}
          className="min-w-0 flex-1 bg-transparent text-15 text-dracula-fg outline-none placeholder:text-[#8b92b8]"
          {...rest}
        />
      </span>
      {error ? (
        <span id={`${id}-error`} className="text-xs text-[#ff8a8a]">{error}</span>
      ) : hint ? (
        <span id={`${id}-hint`} className="text-xs text-tm-muted">{hint}</span>
      ) : null}
    </div>
  );
}

/** 폼 전체 오류 */
export function FormError({ children }: { children: ReactNode }) {
  return (
    <p role="alert" className="m-0 rounded-[10px] border border-[#64363f] bg-[#3d252b] px-3.5 py-2.5 text-13 text-[#ff8a8a]">
      {children}
    </p>
  );
}
