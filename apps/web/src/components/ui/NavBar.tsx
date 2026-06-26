"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { cn } from "@/lib/utils";

const NAV_LINKS = [
  { href: "/stocks/search", label: "종목 검색" },
  { href: "/watchlist", label: "관심종목" },
];

export default function NavBar() {
  const pathname = usePathname();
  const [scrolled, setScrolled] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  // Close menu on outside click
  useEffect(() => {
    if (!menuOpen) return;
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [menuOpen]);

  // Close menu on route change
  useEffect(() => setMenuOpen(false), [pathname]);

  return (
    <nav
      className={cn(
        "sticky top-0 z-40 w-full transition-all duration-200",
        scrolled
          ? "bg-[#1e1f29]/80 backdrop-blur-md border-b border-[#44475a]/60"
          : "bg-[#1e1f29] border-b border-[#44475a]"
      )}
    >
      <div className="max-w-6xl mx-auto px-4 sm:px-6 h-14 flex items-center justify-between">
        {/* Logo */}
        <Link
          href="/"
          className="font-bold text-[#bd93f9] text-lg tracking-tight hover:text-[#ff79c6] transition-colors"
        >
          monticker
        </Link>

        {/* Desktop links */}
        <div className="hidden md:flex items-center gap-1">
          {NAV_LINKS.map(({ href, label }) => {
            const active = pathname === href || pathname.startsWith(href + "/");
            return (
              <Link
                key={href}
                href={href}
                className={cn(
                  "px-3 py-1.5 rounded-md text-sm font-medium transition-colors",
                  active
                    ? "text-[#f8f8f2] border-b-2 border-[#bd93f9] rounded-none pb-1"
                    : "text-[#848e9c] hover:text-[#f8f8f2] hover:bg-[#44475a]/40"
                )}
              >
                {label}
              </Link>
            );
          })}
        </div>

        {/* Mobile hamburger */}
        <div className="md:hidden relative" ref={menuRef}>
          <button
            onClick={() => setMenuOpen((v) => !v)}
            aria-label="메뉴 열기"
            aria-expanded={menuOpen}
            className="p-2 rounded-md text-[#848e9c] hover:text-[#f8f8f2] hover:bg-[#44475a]/40 transition-colors"
          >
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
            <div className="absolute right-0 top-full mt-1 w-48 bg-[#21222c] border border-[#44475a] rounded-xl shadow-xl overflow-hidden">
              {NAV_LINKS.map(({ href, label }) => {
                const active =
                  pathname === href || pathname.startsWith(href + "/");
                return (
                  <Link
                    key={href}
                    href={href}
                    className={cn(
                      "block px-4 py-3 text-sm transition-colors",
                      active
                        ? "text-[#f8f8f2] bg-[#bd93f9]/15 font-semibold"
                        : "text-[#848e9c] hover:text-[#f8f8f2] hover:bg-[#44475a]/40"
                    )}
                  >
                    {label}
                  </Link>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </nav>
  );
}
