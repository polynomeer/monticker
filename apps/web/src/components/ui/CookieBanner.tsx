"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import { Btn } from "@/components/terminal/ui";
import { Icon } from "@/components/terminal/Icon";

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
      className="fixed bottom-4 left-4 right-4 z-50 animate-fade-up sm:left-auto sm:max-w-lg"
    >
      <div className="flex flex-col items-start gap-3 rounded-xl border border-tm-line2 bg-tm-panel p-4 text-13 text-tm-soft shadow-[0_8px_32px_rgba(0,0,0,0.45)] sm:flex-row sm:items-center sm:justify-between">
        <p className="m-0 flex items-start gap-2.5 leading-relaxed">
          <Icon name="info" size={16} className="mt-0.5 flex-none text-dracula-cyan" />
          <span>
            monticker는 서비스 개선을 위해 필수 쿠키를 사용합니다.{" "}
            <Link href="/terms" className="text-dracula-purple hover:text-[#d6bcfb]">이용약관</Link>
            {" · "}
            <Link href="/privacy" className="text-dracula-purple hover:text-[#d6bcfb]">개인정보처리방침</Link>
          </span>
        </p>
        <div className="flex flex-none gap-2 self-end sm:self-auto">
          <Btn kind="ghost" size="sm" className="h-8" onClick={decline}>거부</Btn>
          <Btn size="sm" className="h-8" onClick={accept}>동의</Btn>
        </div>
      </div>
    </div>
  );
}
