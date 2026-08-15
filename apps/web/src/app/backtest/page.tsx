"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import BacktestResultView from "@/components/backtest/BacktestResultView";
import { Card } from "@/components/ui/Card";

const STOCKS = [
  { id: 2, symbol: "005930", name: "삼성전자" },
  { id: 3, symbol: "000660", name: "SK하이닉스" },
  { id: 4, symbol: "035420", name: "네이버" },
  { id: 5, symbol: "AAPL",   name: "Apple Inc." },
  { id: 6, symbol: "NVDA",   name: "NVIDIA Corp." },
];

const STRATEGIES = [
  { key: "MA_CROSSOVER", label: "이동평균 크로스오버", desc: "단기MA가 장기MA를 상향돌파할 때 매수" },
  { key: "RSI",          label: "RSI 과매수/과매도",   desc: "RSI 30 이하 매수, 70 이상 매도" },
  { key: "EMA_BREAKOUT", label: "EMA 돌파 전략",       desc: "거래량 급증 + 상승 추세 돌파 시 매수" },
];

interface BacktestResult {
  symbol: string; strategy: string;
  initialCapital: number; finalCapital: number;
  metrics: {
    totalReturn: number; annualizedReturn: number;
    sharpeRatio: number; maxDrawdown: number;
    winRate: number; totalTrades: number;
    profitTrades: number; avgHoldingDays: number;
    avgPnlPct: number; profitFactor: number;
  };
  trades: Array<{
    entryDate: string; exitDate: string;
    entryPrice: number; exitPrice: number;
    quantity: number; pnl: number; pnlPct: number; exitReason: string;
  }>;
  equityCurve: Array<{ date: string; equity: number; drawdown: number }>;
}

export default function BacktestPage() {
  const [stockId,    setStockId]    = useState(2);
  const [strategy,   setStrategy]   = useState("MA_CROSSOVER");
  const [fromDate,   setFromDate]   = useState("2026-05-24");
  const [toDate,     setToDate]     = useState("2026-06-22");
  const [capital,    setCapital]    = useState(10000000);
  const [stopLoss,   setStopLoss]   = useState(5);
  const [takeProfit, setTakeProfit] = useState(10);
  const [submitted,  setSubmitted]  = useState<object | null>(null);

  const { data, isLoading, error } = useQuery<BacktestResult>({
    queryKey: ["backtest", submitted],
    queryFn:  async () => {
      const res = await fetch("/api/backtest", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(submitted),
      });
      if (!res.ok) { const text = await res.text(); let msg = "백테스트 실패"; try { msg = JSON.parse(text).error ?? msg; } catch {} throw new Error(msg + " (" + res.status + ")"); }
      return res.json();
    },
    enabled: !!submitted,
    staleTime: Infinity,
  });

  const handleRun = () => {
    setSubmitted({
      stockId, strategy, fromDate, toDate,
      initialCapital: capital,
      stopLossPct: stopLoss, takeProfitPct: takeProfit,
    });
  };

  return (
    <div className="max-w-5xl mx-auto p-4 sm:p-6 space-y-6 animate-fade-up">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-gray-900 dark:text-[#f8f8f2]">백테스팅</h1>
        <p className="text-sm text-gray-500 dark:text-[#6272a4] mt-1">
          과거 데이터에 전략을 적용해 가상 수익률을 검증합니다.
        </p>
      </div>

      {/* 설정 패널 */}
      <Card className="p-5 grid grid-cols-1 sm:grid-cols-2 gap-4">
        {/* 종목 */}
        <div>
          <label className="text-xs text-gray-500 dark:text-[#6272a4] mb-1 block">종목</label>
          <select value={stockId} onChange={e => setStockId(Number(e.target.value))}
            className="w-full border-gray-300 dark:border-[#44475a] bg-white dark:bg-[#44475a] text-gray-900 dark:text-[#f8f8f2] rounded-lg px-3 py-2 text-sm transition-colors hover:border-gray-400 dark:hover:border-[#6272a4]">
            {STOCKS.map(s => <option key={s.id} value={s.id}>{s.name} ({s.symbol})</option>)}
          </select>
        </div>

        {/* 전략 */}
        <div>
          <label className="text-xs text-gray-500 dark:text-[#6272a4] mb-1 block">전략</label>
          <select value={strategy} onChange={e => setStrategy(e.target.value)}
            className="w-full border-gray-300 dark:border-[#44475a] bg-white dark:bg-[#44475a] text-gray-900 dark:text-[#f8f8f2] rounded-lg px-3 py-2 text-sm transition-colors hover:border-gray-400 dark:hover:border-[#6272a4]">
            {STRATEGIES.map(s => <option key={s.key} value={s.key}>{s.label}</option>)}
          </select>
          <p className="text-[10px] text-gray-400 dark:text-[#6272a4] mt-0.5">
            {STRATEGIES.find(s => s.key === strategy)?.desc}
          </p>
        </div>

        {/* 날짜 */}
        <div>
          <label className="text-xs text-gray-500 dark:text-[#6272a4] mb-1 block">시작일</label>
          <input type="date" value={fromDate} onChange={e => setFromDate(e.target.value)}
            className="w-full border-gray-300 dark:border-[#44475a] bg-white dark:bg-[#44475a] text-gray-900 dark:text-[#f8f8f2] rounded-lg px-3 py-2 text-sm transition-colors hover:border-gray-400 dark:hover:border-[#6272a4]" />
        </div>
        <div>
          <label className="text-xs text-gray-500 dark:text-[#6272a4] mb-1 block">종료일</label>
          <input type="date" value={toDate} onChange={e => setToDate(e.target.value)}
            className="w-full border-gray-300 dark:border-[#44475a] bg-white dark:bg-[#44475a] text-gray-900 dark:text-[#f8f8f2] rounded-lg px-3 py-2 text-sm transition-colors hover:border-gray-400 dark:hover:border-[#6272a4]" />
        </div>

        {/* 초기 자본 */}
        <div>
          <label className="text-xs text-gray-500 dark:text-[#6272a4] mb-1 block">초기 자본 (원)</label>
          <input type="number" value={capital} onChange={e => setCapital(Number(e.target.value))}
            step={1000000} className="w-full border-gray-300 dark:border-[#44475a] bg-white dark:bg-[#44475a] text-gray-900 dark:text-[#f8f8f2] rounded-lg px-3 py-2 text-sm transition-colors hover:border-gray-400 dark:hover:border-[#6272a4]" />
        </div>

        {/* 손절/익절 */}
        <div className="flex gap-3">
          <div className="flex-1">
            <label className="text-xs text-gray-500 dark:text-[#6272a4] mb-1 block">손절 (%)</label>
            <input type="number" value={stopLoss} onChange={e => setStopLoss(Number(e.target.value))}
              step={1} min={1} max={50}
              className="w-full border-gray-300 dark:border-[#44475a] bg-white dark:bg-[#44475a] text-gray-900 dark:text-[#f8f8f2] rounded-lg px-3 py-2 text-sm transition-colors hover:border-gray-400 dark:hover:border-[#6272a4]" />
          </div>
          <div className="flex-1">
            <label className="text-xs text-gray-500 dark:text-[#6272a4] mb-1 block">익절 (%)</label>
            <input type="number" value={takeProfit} onChange={e => setTakeProfit(Number(e.target.value))}
              step={1} min={1} max={200}
              className="w-full border-gray-300 dark:border-[#44475a] bg-white dark:bg-[#44475a] text-gray-900 dark:text-[#f8f8f2] rounded-lg px-3 py-2 text-sm transition-colors hover:border-gray-400 dark:hover:border-[#6272a4]" />
          </div>
        </div>

        {/* 실행 버튼 */}
        <div className="sm:col-span-2">
          <button onClick={handleRun} disabled={isLoading}
            className="w-full py-2.5 rounded-lg font-semibold text-sm bg-blue-600 dark:bg-[#bd93f9] text-white dark:text-[#282a36] hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-50 disabled:active:scale-100">
            {isLoading ? "시뮬레이션 중..." : "백테스트 실행"}
          </button>
          {error && <p className="text-xs text-red-500 dark:text-[#f6465d] mt-1 text-center">{(error as Error).message}</p>}
        </div>
      </Card>

      {/* 결과 */}
      {data && <BacktestResultView result={data} />}
    </div>
  );
}
