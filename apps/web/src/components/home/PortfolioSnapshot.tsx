"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";
import { useQuery } from "@tanstack/react-query";

interface Portfolio {
  cash: number; totalValue: number; totalPnl: number; totalPnlRate: number;
  holdings: Array<{ symbol: string; name: string; pnl: number; pnlRate: number; }>;
}

function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }
function pnlColor(n: number) { return n > 0 ? "text-[#ff5050]" : n < 0 ? "text-[#4a8fd4]" : "dark:text-dracula-comment"; }
function sign(n: number) { return n > 0 ? "+" : ""; }

export default function PortfolioSnapshot() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: portfolio } = useQuery<Portfolio>({
    queryKey: ["paper", "portfolio", "home"],
    queryFn:  async () => {
      const r = await authFetch("/api/paper/portfolio");
      return r.ok ? r.json() : null;
    },
    enabled:         isLoggedIn,
    refetchInterval: 10_000,
    staleTime:       10_000,
  });

  if (!isLoggedIn || !portfolio) return null;

  const hasHoldings = portfolio.holdings?.length > 0;
  const isActive    = hasHoldings || portfolio.totalPnl !== 0;

  if (!isActive) return (
    <div className="border dark:border-dracula-line dark:bg-dracula-bg rounded-xl p-4">
      <div className="flex items-center justify-between mb-2">
        <h2 className="text-sm font-semibold dark:text-dracula-fg">모의 포트폴리오</h2>
        <Link href="/portfolio" className="text-[10px] dark:text-dracula-comment hover:dark:text-dracula-purple">자세히 →</Link>
      </div>
      <p className="text-xs dark:text-dracula-comment py-1">
        초기 자본 ₩{fmt(portfolio.cash)} · <Link href="/portfolio" className="hover:dark:text-dracula-purple">투자 시작하기 →</Link>
      </p>
    </div>
  );

  return (
    <div className="border dark:border-dracula-line dark:bg-dracula-bg rounded-xl p-4">
      <div className="flex items-center justify-between mb-3">
        <h2 className="text-sm font-semibold dark:text-dracula-fg">모의 포트폴리오</h2>
        <Link href="/portfolio" className="text-[10px] dark:text-dracula-comment hover:dark:text-dracula-purple">자세히 →</Link>
      </div>

      <div className="flex items-end justify-between mb-3">
        <div>
          <p className="text-xs dark:text-dracula-comment">총 평가금액</p>
          <p className="text-xl font-bold dark:text-dracula-fg">₩{fmt(portfolio.totalValue)}</p>
        </div>
        <div className="text-right">
          <p className={`text-lg font-bold ${pnlColor(portfolio.totalPnl)}`}>
            {sign(portfolio.totalPnl)}₩{fmt(portfolio.totalPnl)}
          </p>
          <p className={`text-xs ${pnlColor(portfolio.totalPnlRate)}`}>
            {sign(portfolio.totalPnlRate)}{portfolio.totalPnlRate.toFixed(2)}%
          </p>
        </div>
      </div>

      {hasHoldings && (
        <div className="space-y-1">
          {portfolio.holdings.slice(0, 3).map((h: { symbol: string; name: string; pnl: number; pnlRate: number }) => (
            <div key={h.symbol} className="flex items-center justify-between text-xs">
              <span className="dark:text-dracula-comment truncate max-w-[120px]">{h.name}</span>
              <span className={`font-mono font-semibold ${pnlColor(h.pnl)}`}>
                {sign(h.pnlRate)}{h.pnlRate.toFixed(2)}%
              </span>
            </div>
          ))}
          {portfolio.holdings.length > 3 && (
            <p className="text-[10px] dark:text-dracula-line">외 {portfolio.holdings.length - 3}종목</p>
          )}
        </div>
      )}
    </div>
  );
}
