"use client";

import { useEffect, useState } from "react";
import Link from "next/link";

interface AlertHistory {
  id: number;
  ruleId: number;
  stockId: number;
  symbol: string | null;
  stockName: string | null;
  triggeredAt: string;
  message: string;
  deliveryStatus: string;
}

function getAccessToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem("accessToken");
}

async function authFetch(url: string, options?: RequestInit): Promise<Response> {
  const token = getAccessToken();
  return fetch(url, {
    ...options,
    headers: {
      ...(options?.headers ?? {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });
}

const STATUS_BADGE: Record<string, string> = {
  SENT:    "bg-green-100 text-green-700",
  FAILED:  "bg-red-100 text-red-700",
  PENDING: "bg-yellow-100 text-yellow-700",
};

export default function AlertsPage() {
  const [histories, setHistories] = useState<AlertHistory[]>([]);
  const [loading, setLoading] = useState(true);
  const [isLoggedIn, setIsLoggedIn] = useState(false);

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  useEffect(() => {
    if (!isLoggedIn) { setLoading(false); return; }
    authFetch("/api/alerts/histories")
      .then(r => r.ok ? r.json() : [])
      .then(setHistories)
      .finally(() => setLoading(false));
  }, [isLoggedIn]);

  if (!isLoggedIn) return (
    <div className="max-w-2xl mx-auto p-6 text-center py-20">
      <p className="text-gray-500 mb-4">알림 이력을 보려면 로그인이 필요합니다.</p>
      <Link href="/login" className="bg-blue-600 text-white px-6 py-2 rounded-lg">로그인</Link>
    </div>
  );

  return (
    <div className="max-w-2xl mx-auto p-6">
      <h1 className="text-2xl font-bold mb-6">알림 이력</h1>
      {loading && <p className="text-gray-400">불러오는 중...</p>}
      {!loading && histories.length === 0 && (
        <p className="text-gray-400 text-center py-12">아직 발동된 알림이 없습니다.</p>
      )}
      <ul className="space-y-3">
        {histories.map(h => (
          <li key={h.id} className="border border-gray-200 rounded-lg p-4">
            <div className="flex items-start justify-between gap-2">
              <div className="flex-1">
                {h.symbol && (
                  <Link href={`/stocks/${h.symbol}`} className="text-sm font-semibold text-blue-600 hover:underline">
                    {h.stockName} ({h.symbol})
                  </Link>
                )}
                <p className="text-sm text-gray-700 mt-1">{h.message}</p>
                <p className="text-xs text-gray-400 mt-1">
                  {new Date(h.triggeredAt).toLocaleString("ko-KR")}
                </p>
              </div>
              <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${STATUS_BADGE[h.deliveryStatus] ?? "bg-gray-100 text-gray-500"}`}>
                {h.deliveryStatus === "SENT" ? "발송됨" : h.deliveryStatus === "FAILED" ? "실패" : "대기"}
              </span>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
