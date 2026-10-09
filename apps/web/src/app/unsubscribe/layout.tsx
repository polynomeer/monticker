import type { Metadata } from "next";

/** ADR-102 — 토큰이 주소에 있는 화면이라 검색 색인과 리퍼러 전송을 막는다. */
export const metadata: Metadata = {
  title: "수신 거부",
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

export default function UnsubscribeLayout({ children }: { children: React.ReactNode }) {
  return children;
}
