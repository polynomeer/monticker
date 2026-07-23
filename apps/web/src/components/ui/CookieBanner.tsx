"use client";

import { useState, useEffect } from "react";
import Link from "next/link";

export default function CookieBanner() {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (!localStorage.getItem("cookie_consent")) setVisible(true);
  }, []);

  const accept = () => { localStorage.setItem("cookie_consent", "accepted"); setVisible(false); };
  const decline = () => { localStorage.setItem("cookie_consent", "declined"); setVisible(false); };

  if (!visible) return null;

  return (
    <div
      role="dialog"
      aria-label="쿠키 사용 동의"
      className="fixed bottom-0 left-0 right-0 z-50 bg-[#21222c] border-t border-[#44475a] px-4 py-4"
    >
      <div className="max-w-4xl mx-auto flex flex-col sm:flex-row items-start sm:items-center gap-3 justify-between">
        <p className="text-sm text-[#f8f8f2]">
          monticker는 서비스 개선을 위해 필수 쿠키를 사용합니다.{" "}
          <Link href="/privacy" className="text-[#bd93f9] hover:underline">개인정보처리방침</Link>
        </p>
        <div className="flex gap-2 shrink-0">
          <button
            onClick={decline}
            className="px-4 py-1.5 rounded-lg border border-[#44475a] text-[#6272a4] text-sm hover:border-[#6272a4] transition-colors"
          >
            거부
          </button>
          <button
            onClick={accept}
            className="px-4 py-1.5 rounded-lg bg-[#bd93f9] text-[#282a36] text-sm font-semibold hover:bg-[#ff79c6] transition-colors"
          >
            동의
          </button>
        </div>
      </div>
    </div>
  );
}
