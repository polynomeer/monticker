"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const TABS = [
  { href: "/settings/notifications", label: "알림" },
  { href: "/settings/appearance",    label: "화면" },
];

/** 설정 하위 페이지 공통 탭. 알림 ↔ 화면 이동. */
export default function SettingsTabs() {
  const pathname = usePathname();
  return (
    <div className="flex gap-1 mb-6 border-b border-gray-200 dark:border-dracula-line">
      {TABS.map(({ href, label }) => {
        const active = pathname === href;
        return (
          <Link
            key={href}
            href={href}
            className={`px-4 py-2 text-sm font-medium -mb-px border-b-2 transition-colors ${
              active
                ? "border-blue-600 dark:border-dracula-purple text-blue-600 dark:text-dracula-purple"
                : "border-transparent text-gray-500 dark:text-dracula-comment hover:text-gray-800 dark:hover:text-dracula-fg"
            }`}
          >
            {label}
          </Link>
        );
      })}
    </div>
  );
}
