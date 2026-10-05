"use client";
import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import BacktestResultView, { type BacktestResult } from "@/components/backtest/BacktestResultView";
import { Btn, Checkbox, Field, Notice, Panel, PanelCol, PanelRow, SelectBox, TerminalPage } from "@/components/terminal";

// ADR-079 — 비용 가정값(%). 세율은 시기·시장별로 달라 서버가 단정하지 않고 이 값을 그대로 쓴다.
// 국내(KOSPI·KOSDAQ) 종목 매도에만 세금을 적용한다.
const COST = { commissionPct: 0.015, sellTaxPct: 0.18, slippagePct: 0.05 } as const;

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

export default function BacktestPage() {
  const [stockId,    setStockId]    = useState(2);
  const [strategy,   setStrategy]   = useState("MA_CROSSOVER");
  const [fromDate,   setFromDate]   = useState("2026-05-24");
  const [toDate,     setToDate]     = useState("2026-06-22");
  const [capital,    setCapital]    = useState(10000000);
  const [stopLoss,   setStopLoss]   = useState(5);
  const [takeProfit, setTakeProfit] = useState(10);
  const [applyFees,  setApplyFees]  = useState(true);
  const [applySlip,  setApplySlip]  = useState(true);
  const [submitted,  setSubmitted]  = useState<object | null>(null);
  const [ranAt,      setRanAt]      = useState<Date | null>(null);

  const { data, isLoading, error } = useQuery<BacktestResult>({
    queryKey: ["backtest", submitted],
    queryFn:  async () => {
      const res = await fetch("/api/backtest", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(submitted),
      });
      if (!res.ok) { const text = await res.text(); let msg = "백테스트 실패"; try { msg = JSON.parse(text).message ?? msg; } catch {} throw new Error(msg + " (" + res.status + ")"); }
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
      ...(applyFees && { commissionPct: COST.commissionPct, sellTaxPct: COST.sellTaxPct }),
      ...(applySlip && { slippagePct: COST.slippagePct }),
    });
    setRanAt(new Date());
  };

  const stock = STOCKS.find(s => s.id === stockId);
  const strat = STRATEGIES.find(s => s.key === strategy);
  const years = (new Date(toDate).getTime() - new Date(fromDate).getTime()) / (365.25 * 86400_000);

  return (
    <TerminalPage
      title="빠른 백테스트"
      crumb="퀀트랩"
      stats={[
        { label: "종목", value: stock?.name ?? "—" },
        { label: "전략", value: strat?.label ?? "—" },
        { label: "기간", value: Number.isFinite(years) && years > 0 ? (years >= 1 ? `${years.toFixed(1)}년` : `${Math.round(years * 365.25)}일`) : "—" },
        { label: "마지막 실행", value: ranAt ? `${String(ranAt.getHours()).padStart(2, "0")}:${String(ranAt.getMinutes()).padStart(2, "0")}` : "—", tone: ranAt ? undefined : "text-tm-muted" },
      ]}
    >
      <PanelRow>
        <Panel tabs={["설정"]} actions={[]} closable={false} className="flex-[0_1_320px] self-start">
          <SelectBox label="종목" value={stockId} onChange={e => setStockId(Number(e.target.value))}>
            {STOCKS.map(s => <option key={s.id} value={s.id}>{s.name} {s.symbol}</option>)}
          </SelectBox>
          <SelectBox label="전략" value={strategy} onChange={e => setStrategy(e.target.value)}>
            {STRATEGIES.map(s => <option key={s.key} value={s.key}>{s.label}</option>)}
          </SelectBox>
          <span className="-mt-1.5 text-2xs text-tm-muted">{strat?.desc}</span>
          <div className="flex gap-2">
            <Field label="시작일" type="date" value={fromDate} onChange={e => setFromDate(e.target.value)} />
            <Field label="종료일" type="date" value={toDate} onChange={e => setToDate(e.target.value)} />
          </div>
          <Field label="초기 자본" unit="원" type="number" step={1000000} value={capital} onChange={e => setCapital(Number(e.target.value))} />
          <div className="flex gap-2">
            <Field label="손절" unit="%" type="number" step={1} min={1} max={50} value={stopLoss} onChange={e => setStopLoss(Number(e.target.value))} inputClassName="text-down" />
            <Field label="익절" unit="%" type="number" step={1} min={1} max={200} value={takeProfit} onChange={e => setTakeProfit(Number(e.target.value))} inputClassName="text-up" />
          </div>
          <Checkbox checked={applyFees} onChange={setApplyFees} label={`수수료 ${COST.commissionPct}% · 세금 ${COST.sellTaxPct}% 반영`} sub="세금은 국내 종목 매도에만 · 세율은 가정값" />
          <Checkbox checked={applySlip} onChange={setApplySlip} label={`슬리피지 ${COST.slippagePct}% 반영`} sub="매수는 비싸게, 매도는 싸게 체결된 것으로 계산" />
          <Btn icon="play" full size="lg" onClick={handleRun} disabled={isLoading}>
            {isLoading ? "시뮬레이션 중..." : "백테스트 실행"}
          </Btn>
          {error && <Notice tone="danger">{(error as Error).message}</Notice>}
          <Link href="/quant-lab/builder" className="text-xs text-dracula-purple hover:underline">복잡한 조건은 룰셋 빌더에서 →</Link>
        </Panel>

        <PanelCol className="flex-[999_1_640px]">
          {data ? (
            <BacktestResultView result={data} />
          ) : (
            <Panel tabs={["결과 차트"]} actions={[]} closable={false}>
              <div className="grid h-72 place-items-center rounded-lg border border-dashed border-tm-line2 text-center text-13 text-tm-muted">
                {isLoading ? "시뮬레이션 중..." : <div>왼쪽에서 종목·전략·기간을 정하고<br />백테스트를 실행하세요.</div>}
              </div>
            </Panel>
          )}
        </PanelCol>
      </PanelRow>
    </TerminalPage>
  );
}
