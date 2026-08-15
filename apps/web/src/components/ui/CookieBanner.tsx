"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import { Card } from "@/components/ui/Card";

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
      className="fixed bottom-4 left-4 right-4 sm:left-auto sm:max-w-lg z-50 animate-fade-up"
    >
      <Card className="p-4 backdrop-blur-md bg-white/90 dark:bg-[#21222c]/90">
        <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3 justify-between">
          <p className="text-sm text-gray-700 dark:text-[#f8f8f2]">
            monticker는 서비스 개선을 위해 필수 쿠키를 사용합니다.{" "}
            <Link href="/privacy" className="text-blue-600 dark:text-[#bd93f9] hover:underline">개인정보처리방침</Link>
          </p>
          <div className="flex gap-2 shrink-0">
            <button
              onClick={decline}
              className="px-4 py-1.5 rounded-lg border border-gray-300 dark:border-[#44475a] text-gray-500 dark:text-[#6272a4] text-sm hover:border-gray-400 dark:hover:border-[#6272a4] hover:text-gray-700 dark:hover:text-[#f8f8f2] active:scale-95 transition-all duration-150"
            >
              거부
            </button>
            <button
              onClick={accept}
              className="px-4 py-1.5 rounded-lg bg-blue-600 dark:bg-[#bd93f9] text-white dark:text-[#282a36] text-sm font-semibold hover:opacity-90 active:scale-95 transition-all duration-150"
            >
              동의
            </button>
          </div>
        </div>
      </Card>
    </div>
  );
}
