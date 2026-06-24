"use client";
import { useEffect, useState } from "react";
import AlertStats from "@/components/alerts/AlertStats";
import { authFetch } from "@/services/api";

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
    <div className="max-w-3xl mx-auto p-6 space-y-6">
      <h1 className="text-2xl font-bold dark:text-[#f8f8f2]">알림 이력</h1>

      <AlertStats />

      <div className="border dark:border-[#44475a] dark:bg-[#282a36] rounded-xl overflow-hidden">
        <div className="px-4 py-3 border-b dark:border-[#44475a]">
          <span className="text-sm font-semibold dark:text-[#f8f8f2]">알림 목록</span>
        </div>
        {loading ? (
          <div className="h-32 animate-pulse dark:bg-[#44475a]/20" />
        ) : !alerts.length ? (
          <p className="text-center py-8 text-sm dark:text-[#6272a4]">알림 이력이 없습니다.</p>
        ) : (
          <ul className="divide-y dark:divide-[#44475a]/40">
            {alerts.map(a => (
              <li key={a.id} className="flex items-start justify-between px-4 py-3 text-sm">
                <div>
                  <p className="font-medium dark:text-[#f8f8f2]">{a.name} <span className="text-xs dark:text-[#6272a4]">({a.symbol})</span></p>
                  <p className="text-xs dark:text-[#6272a4] mt-0.5">{a.message}</p>
                  <p className="text-xs dark:text-[#44475a] mt-0.5">{new Date(a.firedAt).toLocaleString("ko-KR")}</p>
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
      </div>
    </div>
  );
}
