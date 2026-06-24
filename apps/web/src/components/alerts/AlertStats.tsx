"use client";
import { useEffect, useState } from "react";
import { authFetch } from "@/services/api";

interface DailyStat { date: string; count: number; }
interface Stats {
  totalFired: number; totalSent: number; totalFailed: number;
  successRate: number; activeRules: number;
  recentFires: DailyStat[];
}

export default function AlertStats() {
  const [stats, setStats] = useState<Stats | null>(null);

  useEffect(() => {
    authFetch("/api/alerts/stats").then(r => r.ok ? r.json() : null).then(setStats);
  }, []);

  if (!stats) return null;

  const maxCount = Math.max(...stats.recentFires.map(d => d.count), 1);

  return (
    <div className="border dark:border-[#44475a] dark:bg-[#282a36] rounded-xl p-5 space-y-4">
      <h2 className="text-sm font-semibold dark:text-[#f8f8f2]">알림 통계</h2>

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        {[
          { label: "총 발동", value: stats.totalFired },
          { label: "발송 성공", value: stats.totalSent, color: "text-[#0ecb81]" },
          { label: "발송 실패", value: stats.totalFailed, color: "text-[#f6465d]" },
          { label: "활성 규칙", value: stats.activeRules, color: "text-[#bd93f9]" },
        ].map(({ label, value, color }) => (
          <div key={label} className="dark:bg-[#44475a]/20 rounded-lg p-3">
            <p className="text-xs dark:text-[#6272a4]">{label}</p>
            <p className={`text-2xl font-bold ${color ?? "dark:text-[#f8f8f2]"}`}>{value}</p>
          </div>
        ))}
      </div>

      {/* 성공률 바 */}
      <div>
        <div className="flex justify-between text-xs mb-1">
          <span className="dark:text-[#6272a4]">발송 성공률</span>
          <span className="dark:text-[#f8f8f2] font-mono">{stats.successRate.toFixed(1)}%</span>
        </div>
        <div className="h-2 rounded-full dark:bg-[#44475a] overflow-hidden">
          <div className="h-full rounded-full bg-[#0ecb81]" style={{ width: `${stats.successRate}%` }} />
        </div>
      </div>

      {/* 최근 7일 막대 */}
      {stats.recentFires.length > 0 && (
        <div>
          <p className="text-xs dark:text-[#6272a4] mb-2">최근 7일 발동 현황</p>
          <div className="flex items-end gap-1 h-12">
            {stats.recentFires.map(d => (
              <div key={d.date} className="flex-1 flex flex-col items-center gap-0.5">
                <div
                  className="w-full rounded-t bg-[#bd93f9]/70 min-h-[2px]"
                  style={{ height: `${(d.count / maxCount) * 40}px` }}
                  title={`${d.date}: ${d.count}건`}
                />
                <span className="text-[9px] dark:text-[#6272a4]">{d.date.slice(5)}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
