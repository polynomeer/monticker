import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "monticker",
  description: "Event-centric stock observation app",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body className="min-h-screen bg-white">
        <nav className="border-b border-gray-200 px-6 py-3 flex gap-6 text-sm">
          <Link href="/" className="font-bold text-blue-600">monticker</Link>
          <Link href="/stocks/search" className="text-gray-600 hover:text-gray-900">종목 검색</Link>
          <Link href="/watchlist" className="text-gray-600 hover:text-gray-900">관심종목</Link>
        </nav>
        {children}
      </body>
    </html>
  );
}
