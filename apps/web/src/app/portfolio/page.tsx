"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import { getAccessToken } from "@/services/auth";
import { usePaperPortfolio, usePaperHistory, usePaperTrade, type Holding, type TradeHistory } from "@/hooks/usePaperTrade";
import TradeModal from "@/components/paper/TradeModal";
import RiskPanel from "@/components/portfolio/RiskPanel";
import { Card } from "@/components/ui/Card";

function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }
function pct(n: number) { return `${n >= 0 ? "+" : ""}${n.toFixed(2)}%`; }
function pnlColor(n: number) { return n > 0 ? "text-[#ff5050]" : n < 0 ? "text-[#4a8fd4]" : "text-gray-500 dark:text-dracula-comment"; }

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
      <p className="text-gray-500 dark:text-dracula-comment mb-4">모의 투자를 이용하려면 로그인이 필요합니다.</p>
      <Link href="/login" className="inline-block bg-blue-600 dark:bg-dracula-purple dark:text-dracula-bg text-white px-6 py-2 rounded-lg font-medium hover:opacity-90 active:scale-[0.98] transition-all duration-150">로그인</Link>
    </div>
  );

  if (isLoading) return (
    <div className="max-w-3xl mx-auto p-6">
      <div className="h-32 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer mb-4" />
    </div>
  );

  return (
    <div className="max-w-3xl mx-auto p-4 sm:p-6 space-y-5 animate-fade-up">
      {/* 헤더 */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900 dark:text-dracula-fg">모의 포트폴리오</h1>
        <button onClick={handleReset}
          className="text-xs text-gray-500 dark:text-dracula-comment hover:text-market-down border border-gray-300 dark:border-dracula-line px-3 py-1.5 rounded-lg transition-colors">
          초기화
        </button>
      </div>

      {/* 자산 요약 */}
      {portfolio && (
        <Card className="p-5">
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <div>
              <p className="text-xs text-gray-500 dark:text-dracula-comment">총 평가금액</p>
              <p className="text-xl font-bold text-gray-900 dark:text-dracula-fg">₩{fmt(portfolio.totalValue)}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500 dark:text-dracula-comment">가용 현금</p>
              <p className="text-xl font-bold text-gray-900 dark:text-dracula-fg">₩{fmt(portfolio.cash)}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500 dark:text-dracula-comment">총 손익</p>
              <p className={`text-xl font-bold ${pnlColor(portfolio.totalPnl)}`}>
                {portfolio.totalPnl >= 0 ? "+" : ""}₩{fmt(portfolio.totalPnl)}
              </p>
            </div>
            <div>
              <p className="text-xs text-gray-500 dark:text-dracula-comment">수익률</p>
              <p className={`text-xl font-bold ${pnlColor(portfolio.totalPnlRate)}`}>
                {pct(portfolio.totalPnlRate)}
              </p>
            </div>
          </div>
        </Card>
      )}

      {/* 종목 매수 검색 */}
      <Card className="p-4">
        <p className="text-xs text-gray-500 dark:text-dracula-comment mb-2 font-medium">종목 매수</p>
        <div className="relative">
          <input
            type="text" value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            placeholder="종목명 또는 티커 검색..."
            className="w-full border border-gray-300 dark:border-dracula-line bg-white dark:bg-dracula-line/30 text-gray-900 dark:text-dracula-fg
                       dark:placeholder-dracula-comment rounded-lg px-4 py-2.5 text-sm
                       transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:border-dracula-purple"
          />
          {searchResults.length > 0 && (
            <ul className="absolute top-full left-0 right-0 mt-1 animate-fade-up
                           bg-white dark:bg-dracula-bg border border-gray-200 dark:border-dracula-line
                           rounded-xl shadow-xl z-20 overflow-hidden">
              {searchResults.map(s => (
                <li key={s.id}>
                  <button
                    onMouseDown={() => selectStockForBuy(s)}
                    className="w-full flex items-center justify-between px-4 py-3
                               hover:bg-gray-50 dark:hover:bg-dracula-line/30 text-sm transition-colors">
                    <div className="text-left">
                      <p className="font-medium text-gray-900 dark:text-dracula-fg">{s.name}</p>
                      <p className="text-xs text-gray-500 dark:text-dracula-comment">{s.symbol}</p>
                    </div>
                    <span className="text-xs text-blue-600 dark:text-dracula-purple">매수 →</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </Card>

      {/* 보유 종목 */}
      <Card className="overflow-hidden">
        <div className="px-5 py-3 border-b border-gray-200 dark:border-dracula-line bg-gray-50 dark:bg-transparent flex items-center justify-between">
          <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">보유 종목</span>
          <span className="text-xs text-gray-500 dark:text-dracula-comment">{portfolio?.holdings.length ?? 0}개</span>
        </div>
        {!portfolio?.holdings.length ? (
          <p className="text-center py-8 text-sm text-gray-500 dark:text-dracula-comment">보유 종목이 없습니다.</p>
        ) : (
          <div className="divide-y divide-gray-100 dark:divide-dracula-line/40">
            {portfolio.holdings.map((h: Holding) => (
              <div key={h.stockId} className="px-5 py-4 flex items-center gap-3">
                <div className="flex-1 min-w-0">
                  <Link href={`/stocks/${h.symbol}`}
                    className="font-medium text-gray-900 dark:text-dracula-fg hover:text-blue-600 dark:hover:text-dracula-purple truncate block">
                    {h.name}
                  </Link>
                  <p className="text-xs text-gray-500 dark:text-dracula-comment">
                    {h.quantity}주 · 평단 ₩{fmt(h.avgPrice)}
                  </p>
                </div>
                <div className="text-right shrink-0">
                  <p className="font-mono font-semibold text-gray-900 dark:text-dracula-fg">₩{fmt(h.value)}</p>
                  <p className={`text-xs font-mono ${pnlColor(h.pnl)}`}>
                    {pct(h.pnlRate)}
                  </p>
                </div>
                <button
                  onClick={() => setSellModal({
                    stockId: h.stockId, symbol: h.symbol, name: h.name,
                    currentPrice: h.currentPrice, quantity: h.quantity,
                  })}
                  className="shrink-0 text-xs px-3 py-1.5 rounded-lg font-semibold text-white bg-[#4a8fd4] hover:opacity-80 active:scale-95 transition-all duration-150">
                  매도
                </button>
              </div>
            ))}
          </div>
        )}
      </Card>

      {/* 리스크 지표 */}
      <RiskPanel />

      {/* 거래 내역 */}
      <Card className="overflow-hidden">
        <div className="px-5 py-3 border-b border-gray-200 dark:border-dracula-line bg-gray-50 dark:bg-transparent">
          <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">거래 내역</span>
        </div>
        {!history.length ? (
          <p className="text-center py-8 text-sm text-gray-500 dark:text-dracula-comment">거래 내역이 없습니다.</p>
        ) : (
          <ul className="divide-y divide-gray-100 dark:divide-dracula-line/40">
            {history.map((h: TradeHistory) => (
              <li key={h.id} className="flex items-center justify-between px-5 py-3">
                <div className="flex items-center gap-3">
                  <span className={`text-xs font-bold px-2 py-0.5 rounded ${
                    h.side === "BUY"
                      ? "bg-[#ff5050]/20 text-[#ff5050]"
                      : "bg-[#4a8fd4]/20 text-[#4a8fd4]"
                  }`}>{h.side === "BUY" ? "매수" : "매도"}</span>
                  <div>
                    <p className="text-sm font-medium text-gray-900 dark:text-dracula-fg">{h.name ?? h.symbol}</p>
                    <p className="text-xs text-gray-500 dark:text-dracula-comment">
                      {new Date(h.tradedAt).toLocaleString("ko-KR")}
                    </p>
                  </div>
                </div>
                <div className="text-right">
                  <p className="font-mono text-sm text-gray-900 dark:text-dracula-fg">{h.quantity}주</p>
                  <p className="text-xs text-gray-500 dark:text-dracula-comment">₩{fmt(h.price)}/주</p>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

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
