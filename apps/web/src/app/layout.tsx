import type { Metadata } from "next";
import "./globals.css";
import ThemeProvider from "@/components/ThemeProvider";
import QueryProvider from "@/components/QueryProvider";
import NavBar from "@/components/ui/NavBar";
import { ToastContainer } from "@/components/ui/Toast";
import ScrollToTop from "@/components/ui/ScrollToTop";
import CookieBanner from "@/components/ui/CookieBanner";

export const metadata: Metadata = {
  title: { default: "monticker", template: "%s | monticker" },
  description: "실시간 주식 시세, AI 요약, 맞춤형 알림을 한 곳에서. 이벤트 중심 주식 관찰 앱.",
  metadataBase: new URL(process.env.NEXT_PUBLIC_BASE_URL ?? "https://monticker.io"),
  openGraph: {
    type: "website",
    siteName: "monticker",
    title: "monticker — 실시간 주식 관찰 앱",
    description: "실시간 주식 시세, AI 요약, 맞춤형 알림을 한 곳에서.",
    images: [{ url: "/og-image.png", width: 1200, height: 630, alt: "monticker" }],
  },
  twitter: {
    card: "summary_large_image",
    title: "monticker — 실시간 주식 관찰 앱",
    description: "실시간 주식 시세, AI 요약, 맞춤형 알림을 한 곳에서.",
    images: ["/og-image.png"],
  },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" suppressHydrationWarning>
      <body className="min-h-screen font-sans antialiased bg-[#f8f8f2] dark:bg-dracula-bg text-gray-900 dark:text-dracula-fg bg-mesh-light dark:bg-mesh-dark bg-no-repeat bg-fixed">
        <QueryProvider>
          <ThemeProvider>
            <NavBar />
            <main className="pt-2">{children}</main>
            <ToastContainer />
            <ScrollToTop />
            <CookieBanner />
          </ThemeProvider>
        </QueryProvider>
      </body>
    </html>
  );
}
