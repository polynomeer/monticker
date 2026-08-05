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
      "sticky top-0 z-40 w-full transition-all duration-200",
      scrolled
        ? "bg-white/80 dark:bg-[#21222c]/80 backdrop-blur-md border-b border-gray-200 dark:border-[#44475a]/60"
        : "bg-white dark:bg-[#21222c] border-b border-gray-200 dark:border-[#44475a]"
    )}>
      <div className="max-w-7xl mx-auto px-4 sm:px-6 h-14 flex items-center gap-4">

        {/* 로고 */}
        <Link href="/"
          className="font-bold text-blue-600 dark:text-[#bd93f9] text-lg tracking-tight shrink-0 hover:opacity-80 transition-opacity">
          monticker
        </Link>

        {/* 검색 (데스크탑) */}
        <div className="hidden md:block">
          <SearchAutocomplete />
        </div>

        {/* 데스크탑 링크 */}
        <div className="hidden md:flex items-center gap-0.5 ml-2">
          {NAV_LINKS.map(({ href, label }) => {
            const active = pathname === href || pathname.startsWith(href + "/");
            return (
              <Link key={href} href={href}
                className={cn(
                  "px-3 py-1.5 rounded-md text-sm font-medium transition-colors whitespace-nowrap",
                  active
                    ? "text-gray-900 dark:text-[#f8f8f2] bg-gray-100 dark:bg-[#44475a]/50 font-semibold"
                    : "text-gray-500 dark:text-[#848e9c] hover:text-gray-900 dark:hover:text-[#f8f8f2] hover:bg-gray-50 dark:hover:bg-[#44475a]/30"
                )}>
                {label}
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
              className="p-2 rounded-md text-gray-500 dark:text-[#848e9c] hover:text-gray-900 dark:hover:text-[#f8f8f2] hover:bg-gray-100 dark:hover:bg-[#44475a]/40 transition-colors">
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
              <div className="absolute right-0 top-full mt-1 w-56 bg-white dark:bg-[#21222c] border border-gray-200 dark:border-[#44475a] rounded-xl shadow-xl overflow-hidden">
                {/* 모바일 검색 */}
                <div className="p-3 border-b border-gray-100 dark:border-[#44475a]">
                  <SearchAutocomplete />
                </div>
                {/* 모바일 링크 */}
                {NAV_LINKS.map(({ href, label }) => {
                  const active = pathname === href || pathname.startsWith(href + "/");
                  return (
                    <Link key={href} href={href}
                      className={cn(
                        "block px-4 py-3 text-sm transition-colors",
                        active
                          ? "text-gray-900 dark:text-[#f8f8f2] bg-gray-50 dark:bg-[#bd93f9]/15 font-semibold"
                          : "text-gray-500 dark:text-[#848e9c] hover:text-gray-900 dark:hover:text-[#f8f8f2] hover:bg-gray-50 dark:hover:bg-[#44475a]/30"
                      )}>
                      {label}
                    </Link>
                  );
                })}
                {/* 모바일 AuthNav */}
                <div className="px-4 py-3 border-t border-gray-100 dark:border-[#44475a]">
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
