"use client";
import { useEffect, useState } from "react";
import { Bell } from "@phosphor-icons/react";
import AlertStats from "@/components/alerts/AlertStats";
import EmptyState from "@/components/common/EmptyState";
import { Card } from "@/components/ui/Card";
import { authFetch } from "@/services/api";
import { useRouter } from "next/navigation";

interface AlertHistory {
  id: number;
  symbol: string;
  name: string;
  ruleType: string;
  message: string;
  firedAt: string;
  sentAt: string | null;
  status: string;
}

export default function AlertsPage() {
  const [alerts, setAlerts] = useState<AlertHistory[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    authFetch("/api/alerts/history")
      .then(r => r.ok ? r.json() : [])
      .then((data) => { setAlerts(data); setLoading(false); });
  }, []);

  return (
    <div className="max-w-3xl mx-auto p-4 sm:p-6 space-y-6 animate-fade-up">
      <h1 className="text-2xl font-bold tracking-tight text-gray-900 dark:text-[#f8f8f2]">알림 이력</h1>

      <AlertStats />

      <Card className="overflow-hidden">
        <div className="px-4 py-3 border-b border-gray-200 dark:border-[#44475a] bg-gray-50 dark:bg-transparent">
          <span className="text-sm font-semibold text-gray-900 dark:text-[#f8f8f2]">알림 목록</span>
        </div>
        {loading ? (
          <div className="h-32 bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />
        ) : !alerts.length ? (
          <EmptyState icon={Bell} title="알림 이력이 없습니다" description="종목 상세 페이지에서 가격 알림을 설정해보세요." />
        ) : (
          <ul className="divide-y divide-gray-100 dark:divide-[#44475a]/40">
            {alerts.map(a => (
              <li key={a.id} className="flex items-start justify-between px-4 py-3 text-sm hover:bg-gray-50 dark:hover:bg-[#44475a]/10 transition-colors">
                <div>
                  <p className="font-medium text-gray-900 dark:text-[#f8f8f2]">{a.name} <span className="text-xs text-gray-500 dark:text-[#6272a4]">({a.symbol})</span></p>
                  <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-0.5">{a.message}</p>
                  <p className="text-xs text-gray-400 dark:text-[#44475a] mt-0.5">{new Date(a.firedAt).toLocaleString("ko-KR")}</p>
                </div>
                <span className={`text-xs font-bold px-2 py-0.5 rounded mt-0.5 ${
                  a.status === "SENT" ? "bg-[#0ecb81]/20 text-[#0ecb81]" :
                  a.status === "FAILED" ? "bg-[#f6465d]/20 text-[#f6465d]" :
                  "bg-[#6272a4]/20 text-[#6272a4]"
                }`}>
                  {a.status}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
