"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Btn, Divider, Icon, Panel, type IconName } from "@/components/terminal";
import { useAuth } from "@/hooks/useAuth";
import { cn } from "@/lib/utils";

const ITEMS: { label: string; href: string; icon: IconName }[] = [
  { label: "화면", href: "/settings/appearance", icon: "sun" },
  { label: "알림", href: "/settings/notifications", icon: "bell" },
  { label: "구독", href: "/subscription", icon: "card" },
  { label: "증권사 연동", href: "/brokerage/connect", icon: "key" },
  { label: "리스크 한도", href: "/risk", icon: "shield" },
  { label: "약관·개인정보", href: "/terms", icon: "doc" },
];

/** 설정 화면 왼쪽 메뉴 패널 — 시안 p_account.settings_nav() */
export function SettingsNav() {
  const pathname = usePathname();
  const { isLoggedIn, logout } = useAuth();
  return (
    <Panel tabs={["설정"]} actions={[]} closable={false} className="flex-[0_1_240px] self-start max-md:flex-[1_1_100%]">
      <nav aria-label="설정 메뉴" className="flex flex-col gap-0.5">
        {ITEMS.map((it) => {
          const on = pathname === it.href;
          return (
            <Link
              key={it.href}
              href={it.href}
              aria-current={on ? "page" : undefined}
              className={cn(
                "flex h-10 items-center gap-2.5 rounded-lg px-3 text-sm",
                on ? "bg-tm-raised font-semibold text-dracula-fg" : "text-tm-soft hover:bg-tm-raised/60 hover:text-dracula-fg",
              )}
            >
              <Icon name={it.icon} size={17} />
              {it.label}
            </Link>
          );
        })}
      </nav>
      {isLoggedIn && (
        <>
          <Divider />
          <Btn kind="ghost" full onClick={logout}>로그아웃</Btn>
        </>
      )}
    </Panel>
  );
}
