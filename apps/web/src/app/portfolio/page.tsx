"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import { getAccessToken } from "@/services/auth";
import { usePaperPortfolio, usePaperHistory, usePaperTrade, type Holding, type TradeHistory } from "@/hooks/usePaperTrade";
import TradeModal from "@/components/paper/TradeModal";
import RiskPanel from "@/components/portfolio/RiskPanel";

function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }
function pct(n: number) { return `${n >= 0 ? "+" : ""}${n.toFixed(2)}%`; }
function pnlColor(n: number) { return n > 0 ? "text-[#ff5050]" : n < 0 ? "text-[#4a8fd4]" : "dark:text-[#6272a4]"; }

export default function PortfolioPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [sellModal, setSellModal] = useState<{
    stockId: number; symbol: string; name: string;
    currentPrice: number; quantity: number;
  } | null>(null);
  const [showBuyModal, setShowBuyModal] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<
    Array<{ id: number; symbol: string; name: string }>
  >([]);
  const [selectedStock, setSelectedStock] = useState<
    { id: number; symbol: string; name: string; price: number } | null
  >(null);

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: portfolio, isLoading } = usePaperPortfolio();
  const { data: history = [] } = usePaperHistory();
  const { reset } = usePaperTrade();

  // 종목 검색
  useEffect(() => {
    if (searchQuery.length < 1) { setSearchResults([]); return; }
    const timer = setTimeout(async () => {
      const r = await fetch(`/api/stocks/search?query=${encodeURIComponent(searchQuery)}`);
      if (r.ok) setSearchResults((await r.json()).slice(0, 6));
    }, 200);
    return () => clearTimeout(timer);
  }, [searchQuery]);

  const selectStockForBuy = async (stock: { id: number; symbol: string; name: string }) => {
    const r = await fetch(`/api/stocks/${stock.id}/price`);
    const data = r.ok ? await r.json() : null;
    setSelectedStock({ ...stock, price: data?.price ?? 0 });
    setSearchQuery("");
    setSearchResults([]);
    setShowBuyModal(true);
  };

  const handleReset = async () => {
    if (!confirm("계좌를 초기화하면 모든 거래 내역이 삭제됩니다.")) return;
    await reset.mutateAsync();
  };

  if (!isLoggedIn) return (
    <div className="max-w-3xl mx-auto p-6 text-center py-20">
      <p className="dark:text-[#6272a4] mb-4">모의 투자를 이용하려면 로그인이 필요합니다.</p>
      <Link href="/login" className="bg-blue-600 dark:bg-[#bd93f9] dark:text-[#282a36] text-white px-6 py-2 rounded-lg">로그인</Link>
    </div>
  );

  if (isLoading) return (
    <div className="max-w-3xl mx-auto p-6">
      <div className="h-32 dark:bg-[#44475a]/20 rounded-xl animate-pulse mb-4" />
    </div>
  );

  return (
    <div className="max-w-3xl mx-auto p-6 space-y-5">
      {/* 헤더 */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold dark:text-[#f8f8f2]">모의 포트폴리오</h1>
        <button onClick={handleReset}
          className="text-xs dark:text-[#6272a4] hover:text-[#f6465d] border dark:border-[#44475a] px-3 py-1.5 rounded-lg">
          초기화
        </button>
      </div>

      {/* 자산 요약 */}
      {portfolio && (
        <div className="border dark:border-[#44475a] dark:bg-[#282a36] rounded-xl p-5">
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <div>
              <p className="text-xs dark:text-[#6272a4]">총 평가금액</p>
              <p className="text-xl font-bold dark:text-[#f8f8f2]">₩{fmt(portfolio.totalValue)}</p>
            </div>
            <div>
              <p className="text-xs dark:text-[#6272a4]">가용 현금</p>
              <p className="text-xl font-bold dark:text-[#f8f8f2]">₩{fmt(portfolio.cash)}</p>
            </div>
            <div>
              <p className="text-xs dark:text-[#6272a4]">총 손익</p>
              <p className={`text-xl font-bold ${pnlColor(portfolio.totalPnl)}`}>
                {portfolio.totalPnl >= 0 ? "+" : ""}₩{fmt(portfolio.totalPnl)}
              </p>
            </div>
            <div>
              <p className="text-xs dark:text-[#6272a4]">수익률</p>
              <p className={`text-xl font-bold ${pnlColor(portfolio.totalPnlRate)}`}>
                {pct(portfolio.totalPnlRate)}
              </p>
            </div>
          </div>
        </div>
      )}

      {/* 종목 매수 검색 */}
      <div className="border dark:border-[#44475a] dark:bg-[#282a36] rounded-xl p-4">
        <p className="text-xs dark:text-[#6272a4] mb-2 font-medium">종목 매수</p>
        <div className="relative">
          <input
            type="text" value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            placeholder="종목명 또는 티커 검색..."
            className="w-full border dark:border-[#44475a] dark:bg-[#44475a]/30 dark:text-[#f8f8f2]
                       dark:placeholder-[#6272a4] rounded-lg px-4 py-2.5 text-sm
                       focus:outline-none focus:dark:border-[#bd93f9]"
          />
          {searchResults.length > 0 && (
            <ul className="absolute top-full left-0 right-0 mt-1
                           dark:bg-[#282a36] border dark:border-[#44475a]
                           rounded-xl shadow-xl z-20 overflow-hidden">
              {searchResults.map(s => (
                <li key={s.id}>
                  <button
                    onMouseDown={() => selectStockForBuy(s)}
                    className="w-full flex items-center justify-between px-4 py-3
                               hover:dark:bg-[#44475a]/30 text-sm">
                    <div className="text-left">
                      <p className="font-medium dark:text-[#f8f8f2]">{s.name}</p>
                      <p className="text-xs dark:text-[#6272a4]">{s.symbol}</p>
                    </div>
                    <span className="text-xs dark:text-[#bd93f9]">매수 →</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      {/* 보유 종목 */}
      <div className="border dark:border-[#44475a] dark:bg-[#282a36] rounded-xl overflow-hidden">
        <div className="px-5 py-3 border-b dark:border-[#44475a] flex items-center justify-between">
          <span className="text-sm font-semibold dark:text-[#f8f8f2]">보유 종목</span>
          <span className="text-xs dark:text-[#6272a4]">{portfolio?.holdings.length ?? 0}개</span>
        </div>
        {!portfolio?.holdings.length ? (
          <p className="text-center py-8 text-sm dark:text-[#6272a4]">보유 종목이 없습니다.</p>
        ) : (
          <div className="divide-y dark:divide-[#44475a]/40">
            {portfolio.holdings.map((h: Holding) => (
              <div key={h.stockId} className="px-5 py-4 flex items-center gap-3">
                <div className="flex-1 min-w-0">
                  <Link href={`/stocks/${h.symbol}`}
                    className="font-medium dark:text-[#f8f8f2] hover:dark:text-[#bd93f9] truncate block">
                    {h.name}
                  </Link>
                  <p className="text-xs dark:text-[#6272a4]">
                    {h.quantity}주 · 평단 ₩{fmt(h.avgPrice)}
                  </p>
                </div>
                <div className="text-right shrink-0">
                  <p className="font-mono font-semibold dark:text-[#f8f8f2]">₩{fmt(h.value)}</p>
                  <p className={`text-xs font-mono ${pnlColor(h.pnl)}`}>
                    {pct(h.pnlRate)}
                  </p>
                </div>
                <button
                  onClick={() => setSellModal({
                    stockId: h.stockId, symbol: h.symbol, name: h.name,
                    currentPrice: h.currentPrice, quantity: h.quantity,
                  })}
                  className="shrink-0 text-xs px-3 py-1.5 rounded-lg font-semibold text-white bg-[#4a8fd4] hover:opacity-80">
                  매도
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 리스크 지표 */}
      <RiskPanel />

      {/* 거래 내역 */}
      <div className="border dark:border-[#44475a] dark:bg-[#282a36] rounded-xl overflow-hidden">
        <div className="px-5 py-3 border-b dark:border-[#44475a]">
          <span className="text-sm font-semibold dark:text-[#f8f8f2]">거래 내역</span>
        </div>
        {!history.length ? (
          <p className="text-center py-8 text-sm dark:text-[#6272a4]">거래 내역이 없습니다.</p>
        ) : (
          <ul className="divide-y dark:divide-[#44475a]/40">
            {history.map((h: TradeHistory) => (
              <li key={h.id} className="flex items-center justify-between px-5 py-3">
                <div className="flex items-center gap-3">
                  <span className={`text-xs font-bold px-2 py-0.5 rounded ${
                    h.side === "BUY"
                      ? "bg-[#ff5050]/20 text-[#ff5050]"
                      : "bg-[#4a8fd4]/20 text-[#4a8fd4]"
                  }`}>{h.side === "BUY" ? "매수" : "매도"}</span>
                  <div>
                    <p className="text-sm font-medium dark:text-[#f8f8f2]">{h.name ?? h.symbol}</p>
                    <p className="text-xs dark:text-[#6272a4]">
                      {new Date(h.tradedAt).toLocaleString("ko-KR")}
                    </p>
                  </div>
                </div>
                <div className="text-right">
                  <p className="font-mono text-sm dark:text-[#f8f8f2]">{h.quantity}주</p>
                  <p className="text-xs dark:text-[#6272a4]">₩{fmt(h.price)}/주</p>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* 매도 모달 */}
      {sellModal && (
        <TradeModal
          stock={{ id: sellModal.stockId, symbol: sellModal.symbol, name: sellModal.name }}
          currentPrice={sellModal.currentPrice}
          side="SELL"
          maxQuantity={sellModal.quantity}
          onClose={() => setSellModal(null)}
        />
      )}

      {/* 매수 모달 (검색 후) */}
      {showBuyModal && selectedStock && (
        <TradeModal
          stock={{ id: selectedStock.id, symbol: selectedStock.symbol, name: selectedStock.name }}
          currentPrice={selectedStock.price}
          side="BUY"
          onClose={() => { setShowBuyModal(false); setSelectedStock(null); }}
        />
      )}
    </div>
  );
}
