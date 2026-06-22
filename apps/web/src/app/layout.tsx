import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";
import ThemeProvider from "@/components/ThemeProvider";
import ThemeToggle from "@/components/ThemeToggle";

export const metadata: Metadata = {
  title: "monticker",
  description: "Event-centric stock observation app",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" suppressHydrationWarning>
      <body className="min-h-screen bg-white dark:bg-gray-900 text-gray-900 dark:text-gray-100">
        <ThemeProvider>
          <nav className="border-b border-gray-200 dark:border-gray-700 px-6 py-3 flex gap-6 text-sm bg-white dark:bg-gray-900 items-center">
            <Link href="/" className="font-bold text-blue-600">monticker</Link>
            <Link href="/stocks/search" className="text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100">종목 검색</Link>
            <Link href="/watchlist" className="text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100">관심종목</Link>
            <div className="ml-auto">
              <ThemeToggle />
            </div>
          </nav>
          {children}
        </ThemeProvider>
      </body>
    </html>
  );
}
