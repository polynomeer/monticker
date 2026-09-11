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
      {/*
        suppressHydrationWarning on <html>/<body> only silences mismatches in
        that element's OWN attributes/text — it does not cover child-node
        mismatches (e.g. a browser extension inserting a DOM node into <body>
        before hydration; confirmed by reproducing this locally).

        Separately, and more likely what recurs in this app: production
        builds can throw a *nondeterministic* "Hydration failed" (minified
        React error #418) under server/CPU load with no app-code mismatch at
        all — React's concurrent renderer racing hydration against other
        work. Confirmed locally (0/60 hits serially, occasional hits only
        under concurrent requests) and matches a long-standing, still-open
        upstream issue: https://github.com/vercel/next.js/issues/43159.
        There is no known app-level fix for that class; it self-heals via
        client re-render with no visible symptom. See e2e/hydration.spec.ts
        for the regression guard this repo CAN enforce (no *app-introduced*
        SSR/CSR mismatch on first load).
      */}
      <body
        suppressHydrationWarning
        className="min-h-screen font-sans antialiased bg-dracula-fg dark:bg-dracula-bg text-gray-900 dark:text-dracula-fg bg-mesh-light dark:bg-mesh-dark bg-no-repeat bg-fixed"
      >
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
