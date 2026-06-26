import type { Metadata } from "next";
import "./globals.css";
import ThemeProvider from "@/components/ThemeProvider";
import QueryProvider from "@/components/QueryProvider";
import NavBar from "@/components/ui/NavBar";
import { ToastContainer } from "@/components/ui/Toast";

export const metadata: Metadata = {
  title: "monticker",
  description: "Event-centric stock observation app",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" suppressHydrationWarning>
      <body className="min-h-screen bg-[#f8f8f2] dark:bg-[#282a36] text-gray-900 dark:text-[#f8f8f2]">
        <QueryProvider>
          <ThemeProvider>
            <NavBar />
            <main className="pt-2">{children}</main>
            <ToastContainer />
          </ThemeProvider>
        </QueryProvider>
      </body>
    </html>
  );
}
