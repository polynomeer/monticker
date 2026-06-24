"use client";

import { useEffect, useState } from "react";
import RiskPanel from "@/components/portfolio/RiskPanel";

interface HoldingItem {
  stockId: number;
  symbol: string;
  name: string;
  quantity: number;
  avgPrice: number;
  currentPrice: number;
  value: number;
  pnlPct: number;
}

interface Portfolio {
  cash: number;
  totalValue: number;
  holdings: HoldingItem[];
}

interface TradeRecord {
  id: number;
  stockId: number;
  symbol: string;
  side: string;
  quantity: number;
  price: number;
  amount: number;
  tradedAt: string;
}

export default function PortfolioPage() {
  const [portfolio, setPortfolio] = useState<Portfolio | null>(null);
  const [history, setHistory] = useState<TradeRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [stockId, setStockId] = useState("");
  const [qty, setQty] = useState("1");
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);

  const fetchAll = async () => {
    try {
      const [pRes, hRes] = await Promise.all([
        fetch("/api/paper/portfolio"),
        fetch("/api/paper/history"),
      ]);
      if (pRes.ok) setPortfolio(await pRes.json());
      if (hRes.ok) setHistory(await hRes.json());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchAll(); }, []);

  const trade = async (side: "buy" | "sell") => {
    if (!stockId || !qty) return;
    const res = await fetch(`/api/paper/${side}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ stockId: Number(stockId), quantity: Number(qty) }),
    });
    const body = await res.json();
    if (res.ok) {
      setMsg({ text: `${side === "buy" ? "매수" : "매도"} 완료`, ok: true });
      fetchAll();
    } else {
      setMsg({ text: body.error ?? "오류 발생", ok: false });
    }
    setTimeout(() => setMsg(null), 3000);
  };

  const handleReset = async () => {
    if (!confirm("포트폴리오를 초기화하시겠습니까?")) return;
    await fetch("/api/paper/reset", { method: "POST" });
    fetchAll();
  };

  const fmt = (n: number) =>
    new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 }).format(n);

  if (loading) return <div className="p-6 text-gray-500">불러오는 중...</div>;

  return (
    <div className="max-w-3xl mx-auto p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">모의 투자 포트폴리오</h1>
        <button
          onClick={handleReset}
          className="text-xs text-red-500 border border-red-300 rounded px-3 py-1 hover:bg-red-50"
        >
          초기화
        </button>
      </div>

      {/* 자산 요약 */}
      {portfolio && (
        <div className="grid grid-cols-3 gap-3">
          <div className="border border-gray-200 rounded-lg p-4">
            <p className="text-xs text-gray-500 mb-1">총 자산</p>
            <p className="text-xl font-bold">{fmt(portfolio.totalValue)}원</p>
          </div>
          <div className="border border-gray-200 rounded-lg p-4">
            <p className="text-xs text-gray-500 mb-1">현금</p>
            <p className="text-xl font-bold">{fmt(portfolio.cash)}원</p>
          </div>
          <div className="border border-gray-200 rounded-lg p-4">
            <p className="text-xs text-gray-500 mb-1">주식 평가액</p>
            <p className="text-xl font-bold">
              {fmt(portfolio.totalValue - portfolio.cash)}원
            </p>
          </div>
        </div>
      )}

      {/* 거래 입력 */}
      <div className="border border-gray-200 rounded-xl p-4 space-y-3">
        <h2 className="text-sm font-semibold">거래</h2>
        {msg && (
          <p className={`text-sm ${msg.ok ? "text-green-600" : "text-red-500"}`}>{msg.text}</p>
        )}
        <div className="flex gap-2">
          <input
            type="number"
            value={stockId}
            onChange={(e) => setStockId(e.target.value)}
            placeholder="종목 ID"
            className="w-28 border border-gray-300 rounded px-3 py-2 text-sm"
          />
          <input
            type="number"
            value={qty}
            onChange={(e) => setQty(e.target.value)}
            placeholder="수량"
            min={1}
            className="w-20 border border-gray-300 rounded px-3 py-2 text-sm"
          />
          <button
            onClick={() => trade("buy")}
            className="bg-[#0ecb81] text-white px-4 py-2 rounded text-sm font-medium hover:opacity-90"
          >
            매수
          </button>
          <button
            onClick={() => trade("sell")}
            className="bg-[#f6465d] text-white px-4 py-2 rounded text-sm font-medium hover:opacity-90"
          >
            매도
          </button>
        </div>
      </div>

      {/* 보유 종목 */}
      {portfolio && portfolio.holdings.length > 0 && (
        <div className="border border-gray-200 rounded-xl p-4">
          <h2 className="text-sm font-semibold mb-3">보유 종목</h2>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-xs text-gray-500 border-b">
                <th className="text-left pb-2">종목</th>
                <th className="text-right pb-2">수량</th>
                <th className="text-right pb-2">평균단가</th>
                <th className="text-right pb-2">현재가</th>
                <th className="text-right pb-2">평가금액</th>
                <th className="text-right pb-2">손익률</th>
              </tr>
            </thead>
            <tbody>
              {portfolio.holdings.map((h) => (
                <tr key={h.stockId} className="border-b border-gray-50">
                  <td className="py-2">
                    <span className="font-medium">{h.name}</span>
                    <span className="ml-1 text-xs text-gray-400">{h.symbol}</span>
                  </td>
                  <td className="text-right py-2">{h.quantity}</td>
                  <td className="text-right py-2">{fmt(h.avgPrice)}</td>
                  <td className="text-right py-2">{fmt(h.currentPrice)}</td>
                  <td className="text-right py-2">{fmt(h.value)}</td>
                  <td
                    className={`text-right py-2 font-mono font-medium ${
                      h.pnlPct >= 0 ? "text-[#0ecb81]" : "text-[#f6465d]"
                    }`}
                  >
                    {h.pnlPct >= 0 ? "+" : ""}
                    {h.pnlPct.toFixed(2)}%
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* 리스크 지표 */}
      <RiskPanel />

      {/* 거래 내역 */}
      {history.length > 0 && (
        <div className="border border-gray-200 rounded-xl p-4">
          <h2 className="text-sm font-semibold mb-3">거래 내역</h2>
          <div className="space-y-1">
            {history.slice(0, 20).map((t) => (
              <div
                key={t.id}
                className="flex items-center justify-between text-sm py-1 border-b border-gray-50"
              >
                <div className="flex items-center gap-2">
                  <span
                    className={`text-xs font-bold px-1.5 py-0.5 rounded ${
                      t.side === "BUY"
                        ? "bg-[#0ecb81]/10 text-[#0ecb81]"
                        : "bg-[#f6465d]/10 text-[#f6465d]"
                    }`}
                  >
                    {t.side}
                  </span>
                  <span className="font-medium">{t.symbol}</span>
                  <span className="text-gray-400">{t.quantity}주</span>
                </div>
                <div className="text-right">
                  <span className="font-mono">{fmt(t.price)}원</span>
                  <span className="text-xs text-gray-400 ml-2">
                    {new Date(t.tradedAt).toLocaleDateString("ko-KR")}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
