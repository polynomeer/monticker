"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { cn } from "@/lib/utils";
import ThemeToggle from "@/components/ThemeToggle";
import AuthNav from "@/components/AuthNav";
import SearchAutocomplete from "@/components/stock/SearchAutocomplete";

const NAV_LINKS = [
  { href: "/",              label: "스크리너" },
  { href: "/backtest",      label: "백테스팅" },
  { href: "/quant-lab",     label: "Quant Lab" },
  { href: "/matching",      label: "체결엔진" },
  { href: "/risk",          label: "리스크" },
  { href: "/analytics",     label: "Analytics" },
  { href: "/portfolio",     label: "포트폴리오" },
  { href: "/watchlist",     label: "관심종목" },
  { href: "/wallet",        label: "지갑" },
  { href: "/settlement",    label: "정산" },
  { href: "/subscription",  label: "구독" },
  { href: "/alerts",        label: "알림" },
];

export default function NavBar() {
  const pathname  = usePathname();
  const [scrolled,  setScrolled]  = useState(false);
  const [menuOpen,  setMenuOpen]  = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

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

  useEffect(() => setMenuOpen(false), [pathname]);

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
        <div className="hidden md:flex items-center gap-0.5 ml-2 min-w-0 overflow-x-auto fade-edge-x [scrollbar-width:thin]">
          {NAV_LINKS.map(({ href, label }) => {
            const active = pathname === href || pathname.startsWith(href + "/");
            return (
              <Link key={href} href={href}
                className={cn(
                  "relative px-3 py-1.5 rounded-md text-sm font-medium transition-all duration-300 ease-spring whitespace-nowrap",
                  active
                    ? "text-gray-900 dark:text-dracula-fg bg-gray-100 dark:bg-dracula-purple/15 font-semibold"
                    : "text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg hover:bg-gray-50 dark:hover:bg-dracula-line/30"
                )}>
                {label}
                {active && (
                  <span className="absolute left-3 right-3 -bottom-[1px] h-[2px] rounded-full bg-blue-600 dark:bg-dracula-purple animate-pop-in" />
                )}
              </Link>
            );
          })}
        </div>

        {/* 우측 — ThemeToggle + AuthNav */}
        <div className="ml-auto flex items-center gap-2">
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
              <div className="absolute right-0 top-full mt-2 w-56 bg-white dark:bg-dracula-surface border border-gray-200 dark:border-dracula-line rounded-xl shadow-xl dark:shadow-glow-line overflow-hidden animate-pop-in">
                {/* 모바일 검색 */}
                <div className="p-3 border-b border-gray-100 dark:border-dracula-line">
                  <SearchAutocomplete />
                </div>
                {/* 모바일 링크 */}
                <div className="max-h-[60vh] overflow-y-auto">
                  {NAV_LINKS.map(({ href, label }, i) => {
                    const active = pathname === href || pathname.startsWith(href + "/");
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
                {/* 모바일 AuthNav */}
                <div className="px-4 py-3 border-t border-gray-100 dark:border-dracula-line">
                  <AuthNav />
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </nav>
  );
}
