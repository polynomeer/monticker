import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "monticker",
  description: "Event-centric stock observation app",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
