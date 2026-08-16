"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { UserCircle, CreditCard, GearSix, SignOut } from "@phosphor-icons/react";
import { useAuth } from "@/hooks/useAuth";
import { Card } from "@/components/ui/Card";

export const PROFILE_LINKS = [
  { href: "/subscription", label: "구독", icon: CreditCard },
  { href: "/settings/notifications", label: "설정", icon: GearSix },
];

export default function AuthNav() {
  const { isLoggedIn, logout } = useAuth();
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [open]);

  useEffect(() => setOpen(false), [pathname]);

  if (!isLoggedIn) {
    return (
      <Link
        href="/login"
        className="text-sm font-medium px-4 py-1.5 rounded-full bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg hover:opacity-90 hover:scale-[1.03] active:scale-[0.97] transition-all duration-300 ease-spring"
      >
        로그인
      </Link>
    );
  }

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen(v => !v)}
        aria-label="프로필 메뉴"
        aria-expanded={open}
        className="flex items-center justify-center w-9 h-9 rounded-full border border-gray-300 dark:border-dracula-line bg-white dark:bg-dracula-bg text-gray-600 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg hover:border-gray-400 dark:hover:border-dracula-comment active:scale-95 transition-all duration-300 ease-spring"
      >
        <UserCircle size={20} weight="bold" aria-hidden />
      </button>

      {open && (
        <Card outerClassName="absolute right-0 top-full mt-2 w-44 animate-pop-in z-50" className="overflow-hidden">
          <div className="py-1">
            {PROFILE_LINKS.map(({ href, label, icon: Icon }) => (
              <Link
                key={href}
                href={href}
                className="flex items-center gap-2.5 px-4 py-2.5 text-sm text-gray-700 dark:text-dracula-fg hover:bg-gray-50 dark:hover:bg-dracula-line/30 transition-colors"
              >
                <Icon size={16} weight="bold" aria-hidden />
                {label}
              </Link>
            ))}
          </div>
          <div className="border-t border-gray-100 dark:border-dracula-line py-1">
            <button
              onClick={logout}
              className="w-full flex items-center gap-2.5 px-4 py-2.5 text-sm text-market-down hover:bg-gray-50 dark:hover:bg-dracula-line/30 transition-colors text-left"
            >
              <SignOut size={16} weight="bold" aria-hidden />
              로그아웃
            </button>
          </div>
        </Card>
      )}
    </div>
  );
}
