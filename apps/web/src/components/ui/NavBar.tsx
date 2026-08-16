"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { CaretDown, Bell } from "@phosphor-icons/react";
import { cn } from "@/lib/utils";
import { Card } from "@/components/ui/Card";
import ThemeToggle from "@/components/ThemeToggle";
import AuthNav, { PROFILE_LINKS } from "@/components/AuthNav";
import SearchAutocomplete from "@/components/stock/SearchAutocomplete";
import { useAuth } from "@/hooks/useAuth";

const SCREENER = { href: "/", label: "스크리너" };

const NAV_GROUPS = [
  {
    label: "투자 도구",
    items: [
      { href: "/quant-lab", label: "Quant Lab" },
      { href: "/backtest",  label: "백테스팅" },
      { href: "/matching",  label: "체결엔진" },
      { href: "/risk",      label: "리스크" },
      { href: "/analytics", label: "Analytics" },
    ],
  },
  {
    label: "내 자산",
    items: [
      { href: "/portfolio",  label: "포트폴리오" },
      { href: "/wallet",     label: "지갑" },
      { href: "/watchlist",  label: "관심종목" },
      { href: "/settlement", label: "정산" },
    ],
  },
];

function isActiveHref(pathname: string, href: string) {
  return pathname === href || pathname.startsWith(href + "/");
}

function NavGroupButton({
  group, pathname, open, onToggle,
}: {
  group: typeof NAV_GROUPS[number];
  pathname: string;
  open: boolean;
  onToggle: () => void;
}) {
  const active = group.items.some(i => isActiveHref(pathname, i.href));
  return (
    <div className="relative">
      <button
        onClick={onToggle}
        aria-expanded={open}
        className={cn(
          "relative flex items-center gap-1 px-3 py-1.5 rounded-md text-sm font-medium transition-all duration-300 ease-spring whitespace-nowrap",
          active
            ? "text-gray-900 dark:text-dracula-fg bg-gray-100 dark:bg-dracula-purple/15 font-semibold"
            : "text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg hover:bg-gray-50 dark:hover:bg-dracula-line/30"
        )}
      >
        {group.label}
        <CaretDown size={12} weight="bold" className={cn("transition-transform duration-200", open && "rotate-180")} aria-hidden />
        {active && (
          <span className="absolute left-3 right-3 -bottom-[1px] h-[2px] rounded-full bg-blue-600 dark:bg-dracula-purple animate-pop-in" />
        )}
      </button>

      {open && (
        <Card outerClassName="absolute left-0 top-full mt-2 w-44 animate-pop-in z-50" className="overflow-hidden">
          <div className="py-1">
            {group.items.map(({ href, label }) => {
              const itemActive = isActiveHref(pathname, href);
              return (
                <Link
                  key={href}
                  href={href}
                  className={cn(
                    "block px-4 py-2.5 text-sm transition-colors",
                    itemActive
                      ? "text-gray-900 dark:text-dracula-fg bg-gray-50 dark:bg-dracula-purple/15 font-semibold"
                      : "text-gray-700 dark:text-dracula-fg hover:bg-gray-50 dark:hover:bg-dracula-line/30"
                  )}
                >
                  {label}
                </Link>
              );
            })}
          </div>
        </Card>
      )}
    </div>
  );
}

export default function NavBar() {
  const pathname  = usePathname();
  const { isLoggedIn, logout } = useAuth();
  const [scrolled,   setScrolled]   = useState(false);
  const [menuOpen,   setMenuOpen]   = useState(false);
  const [openGroup,  setOpenGroup]  = useState<string | null>(null);
  const menuRef  = useRef<HTMLDivElement>(null);
  const groupsRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  useEffect(() => {
    if (!menuOpen) return;
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node))
        setMenuOpen(false);
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [menuOpen]);

  useEffect(() => {
    if (!openGroup) return;
    const handler = (e: MouseEvent) => {
      if (groupsRef.current && !groupsRef.current.contains(e.target as Node))
        setOpenGroup(null);
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [openGroup]);

  useEffect(() => { setMenuOpen(false); setOpenGroup(null); }, [pathname]);

  return (
    <nav className={cn(
      "sticky top-0 z-40 w-full transition-all duration-300 ease-spring",
      scrolled
        ? "bg-white/75 dark:bg-dracula-surface/70 backdrop-blur-xl border-b border-gray-200/70 dark:border-dracula-line/50 shadow-glow-line"
        : "bg-white dark:bg-dracula-surface border-b border-gray-200 dark:border-dracula-line/80"
    )}>
      <div className="max-w-7xl mx-auto px-4 sm:px-6 h-14 flex items-center gap-4">

        {/* 로고 */}
        <Link href="/"
          className="group flex items-center gap-2 shrink-0">
          <span className="w-2 h-2 rounded-full bg-gradient-to-br from-dracula-purple to-dracula-cyan shadow-glow-purple transition-transform duration-300 ease-spring group-hover:scale-150" />
          <span className="font-bold text-blue-600 dark:text-dracula-fg text-lg tracking-tight transition-colors duration-300 ease-spring group-hover:text-blue-500 dark:group-hover:text-dracula-purple">
            monticker
          </span>
        </Link>

        {/* 검색 (데스크탑) */}
        <div className="hidden md:block">
          <SearchAutocomplete />
        </div>

        {/* 데스크탑 링크 */}
        <div ref={groupsRef} className="hidden md:flex items-center gap-0.5 ml-2 min-w-0">
          <Link href={SCREENER.href}
            className={cn(
              "relative px-3 py-1.5 rounded-md text-sm font-medium transition-all duration-300 ease-spring whitespace-nowrap",
              isActiveHref(pathname, SCREENER.href)
                ? "text-gray-900 dark:text-dracula-fg bg-gray-100 dark:bg-dracula-purple/15 font-semibold"
                : "text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg hover:bg-gray-50 dark:hover:bg-dracula-line/30"
            )}>
            {SCREENER.label}
            {isActiveHref(pathname, SCREENER.href) && (
              <span className="absolute left-3 right-3 -bottom-[1px] h-[2px] rounded-full bg-blue-600 dark:bg-dracula-purple animate-pop-in" />
            )}
          </Link>
          {NAV_GROUPS.map(group => (
            <NavGroupButton
              key={group.label}
              group={group}
              pathname={pathname}
              open={openGroup === group.label}
              onToggle={() => setOpenGroup(v => (v === group.label ? null : group.label))}
            />
          ))}
        </div>

        {/* 우측 — 알림 + ThemeToggle + AuthNav */}
        <div className="ml-auto flex items-center gap-2">
          <Link
            href="/alerts"
            aria-label="알림"
            className={cn(
              "flex items-center justify-center w-9 h-9 rounded-lg border transition-all duration-300 ease-spring active:scale-95",
              isActiveHref(pathname, "/alerts")
                ? "border-blue-600 dark:border-dracula-purple text-blue-600 dark:text-dracula-purple bg-blue-50 dark:bg-dracula-purple/15"
                : "border-gray-300 dark:border-dracula-line text-gray-600 dark:text-dracula-comment bg-white dark:bg-dracula-bg hover:text-gray-900 dark:hover:text-dracula-fg hover:border-gray-400 dark:hover:border-dracula-comment"
            )}
          >
            <Bell size={17} weight="bold" aria-hidden />
          </Link>
          <ThemeToggle />
          <div className="hidden md:block">
            <AuthNav />
          </div>

          {/* 모바일 햄버거 */}
          <div className="md:hidden relative" ref={menuRef}>
            <button onClick={() => setMenuOpen(v => !v)}
              aria-label="메뉴" aria-expanded={menuOpen}
              className="p-2 rounded-md text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg hover:bg-gray-100 dark:hover:bg-dracula-line/40 active:scale-95 transition-all duration-150">
              {menuOpen ? (
                <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M4 4l12 12M16 4L4 16" />
                </svg>
              ) : (
                <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M3 6h14M3 10h14M3 14h14" />
                </svg>
              )}
            </button>

            {menuOpen && (
              <Card
                outerClassName="absolute right-0 top-full mt-2 w-64 animate-pop-in"
                className="overflow-hidden"
              >
                {/* 모바일 검색 */}
                <div className="p-3 border-b border-gray-100 dark:border-dracula-line">
                  <SearchAutocomplete />
                </div>
                {/* 모바일 링크 */}
                <div className="max-h-[60vh] overflow-y-auto">
                  <Link href={SCREENER.href}
                    className={cn(
                      "block px-4 py-3 text-sm transition-colors animate-fade-up",
                      isActiveHref(pathname, SCREENER.href)
                        ? "text-gray-900 dark:text-dracula-fg bg-gray-50 dark:bg-dracula-purple/15 font-semibold"
                        : "text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg hover:bg-gray-50 dark:hover:bg-dracula-line/30"
                    )}>
                    {SCREENER.label}
                  </Link>

                  {NAV_GROUPS.map(group => (
                    <div key={group.label}>
                      <p className="px-4 pt-3 pb-1 text-[11px] font-semibold uppercase tracking-wide text-gray-400 dark:text-dracula-comment/70">
                        {group.label}
                      </p>
                      {group.items.map(({ href, label }, i) => {
                        const active = isActiveHref(pathname, href);
                        return (
                          <Link key={href} href={href}
                            style={{ animationDelay: `${i * 30}ms` }}
                            className={cn(
                              "block px-4 py-3 text-sm transition-colors animate-fade-up",
                              active
                                ? "text-gray-900 dark:text-dracula-fg bg-gray-50 dark:bg-dracula-purple/15 font-semibold"
                                : "text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg hover:bg-gray-50 dark:hover:bg-dracula-line/30"
                            )}>
                            {label}
                          </Link>
                        );
                      })}
                    </div>
                  ))}
                </div>
                {/* 모바일 프로필 */}
                <div className="border-t border-gray-100 dark:border-dracula-line py-1">
                  {isLoggedIn ? (
                    <>
                      {PROFILE_LINKS.map(({ href, label, icon: Icon }) => (
                        <Link key={href} href={href}
                          className="flex items-center gap-2.5 px-4 py-3 text-sm text-gray-700 dark:text-dracula-fg hover:bg-gray-50 dark:hover:bg-dracula-line/30 transition-colors">
                          <Icon size={16} weight="bold" aria-hidden />
                          {label}
                        </Link>
                      ))}
                      <button onClick={logout}
                        className="w-full flex items-center gap-2.5 px-4 py-3 text-sm text-market-down hover:bg-gray-50 dark:hover:bg-dracula-line/30 transition-colors text-left">
                        로그아웃
                      </button>
                    </>
                  ) : (
                    <div className="px-4 py-3">
                      <AuthNav />
                    </div>
                  )}
                </div>
              </Card>
            )}
          </div>
        </div>
      </div>
    </nav>
  );
}
