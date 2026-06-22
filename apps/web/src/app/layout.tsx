import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";
import ThemeProvider from "@/components/ThemeProvider";
import ThemeToggle from "@/components/ThemeToggle";
import AuthNav from "@/components/AuthNav";
import SearchAutocomplete from "@/components/stock/SearchAutocomplete";

export const metadata: Metadata = {
  title: "monticker",
  description: "Event-centric stock observation app",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" suppressHydrationWarning>
      <body className="min-h-screen bg-white dark:bg-[#0b0e11] text-gray-900 dark:text-[#eaecef]">
        <ThemeProvider>
          <nav className="border-b border-gray-200 dark:border-[#2b3240] px-6 py-3 flex items-center gap-4 text-sm bg-white dark:bg-[#1e2329]">
            <Link href="/" className="font-bold text-blue-600 dark:text-[#f0b90b]">monticker</Link>
            <SearchAutocomplete />
            <Link href="/watchlist" className="text-gray-600 hover:text-gray-900 dark:text-[#848e9c] dark:hover:text-[#eaecef]">관심종목</Link>
            <Link href="/alerts" className="text-gray-600 hover:text-gray-900 dark:text-[#848e9c] dark:hover:text-[#eaecef]">알림</Link>
            <Link href="/compare" className="text-gray-600 hover:text-gray-900 dark:text-[#848e9c] dark:hover:text-[#eaecef]">비교</Link>
            <div className="ml-auto flex items-center gap-3">
              <ThemeToggle />
              <AuthNav />
            </div>
          </nav>
          <main>{children}</main>
        </ThemeProvider>
      </body>
    </html>
  );
}
