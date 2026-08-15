"use client";
import { useEffect, useState } from "react";
import { authFetch } from "@/services/api";
import { Card } from "@/components/ui/Card";

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
    <Card className="p-5 space-y-4">
      <h2 className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">알림 통계</h2>

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        {[
          { label: "총 발동", value: stats.totalFired },
          { label: "발송 성공", value: stats.totalSent, color: "text-market-up" },
          { label: "발송 실패", value: stats.totalFailed, color: "text-market-down" },
          { label: "활성 규칙", value: stats.activeRules, color: "text-dracula-purple" },
        ].map(({ label, value, color }) => (
          <div key={label} className="bg-gray-50 dark:bg-dracula-line/20 rounded-lg p-3">
            <p className="text-xs text-gray-500 dark:text-dracula-comment">{label}</p>
            <p className={`text-2xl font-bold tabular-nums ${color ?? "text-gray-900 dark:text-dracula-fg"}`}>{value}</p>
          </div>
        ))}
      </div>

      {/* 성공률 바 */}
      <div>
        <div className="flex justify-between text-xs mb-1">
          <span className="text-gray-500 dark:text-dracula-comment">발송 성공률</span>
          <span className="text-gray-900 dark:text-dracula-fg font-mono tabular-nums">{stats.successRate.toFixed(1)}%</span>
        </div>
        <div className="h-2 rounded-full bg-gray-200 dark:bg-dracula-line overflow-hidden">
          <div className="h-full rounded-full bg-market-up transition-[width] duration-300" style={{ width: `${stats.successRate}%` }} />
        </div>
      </div>

      {/* 최근 7일 막대 */}
      {stats.recentFires.length > 0 && (
        <div>
          <p className="text-xs text-gray-500 dark:text-dracula-comment mb-2">최근 7일 발동 현황</p>
          <div className="flex items-end gap-1 h-12">
            {stats.recentFires.map(d => (
              <div key={d.date} className="flex-1 flex flex-col items-center gap-0.5">
                <div
                  className="w-full rounded-t bg-dracula-purple/70 hover:bg-dracula-purple min-h-[2px] transition-colors"
                  style={{ height: `${(d.count / maxCount) * 40}px` }}
                  title={`${d.date}: ${d.count}건`}
                />
                <span className="text-[9px] text-gray-500 dark:text-dracula-comment">{d.date.slice(5)}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </Card>
  );
}
