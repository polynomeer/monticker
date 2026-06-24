"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";

interface Holding {
  stockId: number; symbol: string; name: string;
  quantity: number; avgPrice: number; currentPrice: number;
  value: number; pnl: number; pnlRate: number;
}
interface Portfolio {
  cash: number; totalValue: number; totalPnl: number; totalPnlRate: number;
  holdings: Holding[];
}
interface TradeHistory {
  id: number; side: string; symbol: string; name: string;
  quantity: number; price: number; amount: number; tradedAt: string;
}

function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }
function pnlColor(n: number) { return n > 0 ? "text-[#ff5050]" : n < 0 ? "text-[#4a8fd4]" : "dark:text-[#6272a4]"; }

export default function PortfolioPage() {
  const [portfolio, setPortfolio] = useState<Portfolio | null>(null);
  const [history, setHistory] = useState<TradeHistory[]>([]);
  const [loading, setLoading] = useState(true);
  const [isLoggedIn, setIsLoggedIn] = useState(false);

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const load = async () => {
    const [pRes, hRes] = await Promise.all([
      authFetch("/api/paper/portfolio"),
      authFetch("/api/paper/history"),
    ]);
    if (pRes.ok) setPortfolio(await pRes.json());
    if (hRes.ok) setHistory(await hRes.json());
    setLoading(false);
  };

  useEffect(() => {
    if (!isLoggedIn) { setLoading(false); return; }
    load();
    const id = setInterval(load, 5000);
    return () => clearInterval(id);
  }, [isLoggedIn]);

  const handleReset = async () => {
    if (!confirm("계좌를 초기화하면 모든 거래 내역이 삭제됩니다. 계속하시겠습니까?")) return;
    await authFetch("/api/paper/reset", { method: "POST" });
    load();
  };

  if (!isLoggedIn) return (
    <div className="max-w-3xl mx-auto p-6 text-center py-20">
      <p className="dark:text-[#6272a4] mb-4">로그인 후 모의 투자를 시작할 수 있습니다.</p>
      <Link href="/login" className="bg-blue-600 dark:bg-[#bd93f9] dark:text-black text-white px-6 py-2 rounded-lg">로그인</Link>
    </div>
  );

  if (loading) return <div className="max-w-3xl mx-auto p-6"><div className="h-40 animate-pulse dark:bg-[#44475a]/20 rounded-lg" /></div>;

  return (
    <div className="max-w-3xl mx-auto p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold dark:text-[#f8f8f2]">모의 포트폴리오</h1>
        <button onClick={handleReset} className="text-xs text-gray-400 dark:text-[#6272a4] hover:text-red-500 dark:hover:text-[#ff5050] border border-gray-200 dark:border-[#44475a] px-3 py-1 rounded">
          초기화
        </button>
      </div>

      {/* 요약 */}
      {portfolio && (
        <div className="border dark:border-[#44475a] dark:bg-[#282a36] rounded-xl p-5 grid grid-cols-2 sm:grid-cols-4 gap-4">
          <div>
            <p className="text-xs dark:text-[#6272a4]">총 평가금액</p>
            <p className="text-lg font-bold dark:text-[#f8f8f2]">₩{fmt(portfolio.totalValue)}</p>
          </div>
          <div>
            <p className="text-xs dark:text-[#6272a4]">현금</p>
            <p className="text-lg font-bold dark:text-[#f8f8f2]">₩{fmt(portfolio.cash)}</p>
          </div>
          <div>
            <p className="text-xs dark:text-[#6272a4]">총 손익</p>
            <p className={`text-lg font-bold ${pnlColor(portfolio.totalPnl)}`}>
              {portfolio.totalPnl >= 0 ? "+" : ""}₩{fmt(portfolio.totalPnl)}
            </p>
          </div>
          <div>
            <p className="text-xs dark:text-[#6272a4]">수익률</p>
            <p className={`text-lg font-bold ${pnlColor(portfolio.totalPnlRate)}`}>
              {portfolio.totalPnlRate >= 0 ? "+" : ""}{portfolio.totalPnlRate.toFixed(2)}%
            </p>
          </div>
        </div>
      )}

      {/* 보유 종목 */}
      <div className="border dark:border-[#44475a] dark:bg-[#282a36] rounded-xl overflow-hidden">
        <div className="px-4 py-3 border-b dark:border-[#44475a]">
          <span className="text-sm font-semibold dark:text-[#f8f8f2]">보유 종목</span>
        </div>
        {!portfolio?.holdings.length ? (
          <p className="text-center py-8 text-sm dark:text-[#6272a4]">보유 종목이 없습니다.</p>
        ) : (
          <table className="w-full text-sm">
            <thead><tr className="border-b dark:border-[#44475a] text-xs dark:text-[#6272a4]">
              <th className="text-left px-4 py-2">종목</th>
              <th className="text-right px-3 py-2">수량</th>
              <th className="text-right px-3 py-2">평균단가</th>
              <th className="text-right px-3 py-2">현재가</th>
              <th className="text-right px-4 py-2">손익</th>
            </tr></thead>
            <tbody>
              {portfolio.holdings.map(h => (
                <tr key={h.stockId} className="border-b dark:border-[#44475a]/40 hover:dark:bg-[#44475a]/10">
                  <td className="px-4 py-2.5">
                    <Link href={`/stocks/${h.symbol}`} className="hover:opacity-80">
                      <p className="font-medium dark:text-[#f8f8f2]">{h.name}</p>
                      <p className="text-xs dark:text-[#6272a4]">{h.symbol}</p>
                    </Link>
                  </td>
                  <td className="text-right px-3 py-2 font-mono dark:text-[#f8f8f2]">{h.quantity}</td>
                  <td className="text-right px-3 py-2 font-mono dark:text-[#6272a4]">{fmt(h.avgPrice)}</td>
                  <td className="text-right px-3 py-2 font-mono dark:text-[#f8f8f2]">{fmt(h.currentPrice)}</td>
                  <td className={`text-right px-4 py-2 font-mono ${pnlColor(h.pnl)}`}>
                    <p>{h.pnl >= 0 ? "+" : ""}{fmt(h.pnl)}</p>
                    <p className="text-xs">{h.pnlRate >= 0 ? "+" : ""}{h.pnlRate.toFixed(2)}%</p>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* 거래 내역 */}
      <div className="border dark:border-[#44475a] dark:bg-[#282a36] rounded-xl overflow-hidden">
        <div className="px-4 py-3 border-b dark:border-[#44475a]">
          <span className="text-sm font-semibold dark:text-[#f8f8f2]">최근 거래</span>
        </div>
        {!history.length ? (
          <p className="text-center py-6 text-sm dark:text-[#6272a4]">거래 내역이 없습니다.</p>
        ) : (
          <ul className="divide-y dark:divide-[#44475a]/40">
            {history.map(h => (
              <li key={h.id} className="flex items-center justify-between px-4 py-2.5 text-sm">
                <div className="flex items-center gap-3">
                  <span className={`text-xs font-bold px-2 py-0.5 rounded ${h.side === "BUY" ? "bg-[#ff5050]/20 text-[#ff5050]" : "bg-[#4a8fd4]/20 text-[#4a8fd4]"}`}>
                    {h.side === "BUY" ? "매수" : "매도"}
                  </span>
                  <div>
                    <p className="font-medium dark:text-[#f8f8f2]">{h.name}</p>
                    <p className="text-xs dark:text-[#6272a4]">{new Date(h.tradedAt).toLocaleString("ko-KR")}</p>
                  </div>
                </div>
                <div className="text-right">
                  <p className="font-mono dark:text-[#f8f8f2]">{h.quantity}주 × {fmt(h.price)}</p>
                  <p className="text-xs dark:text-[#6272a4]">₩{fmt(h.amount)}</p>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
