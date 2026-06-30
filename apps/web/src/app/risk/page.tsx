"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";

interface RiskLimits {
  dailyLossLimitPct: number;
  concentrationLimitPct: number;
  varLimitPct: number;
  maxPositionCount: number;
  maxHourlyOrders: number;
  isActive: boolean;
}

interface ConcentrationItem { stockId: number; symbol: string; valuePct: number; }

interface RiskExposure {
  totalAssets: number;
  availableCash: number;
  dailyPnl: number;
  dailyPnlPct: number;
  topConcentration: ConcentrationItem | null;
  estimatedVaR: number;
  activeOrderCount: number;
  hourlyOrderCount: number;
  limits: RiskLimits;
}

function GaugeMeter({ label, current, limit, unit = "%", invert = false }: {
  label: string; current: number; limit: number; unit?: string; invert?: boolean;
}) {
  // invert=true: current가 낮을수록 좋음 (손실, VaR)
  const ratio = limit > 0 ? Math.min(current / limit, 1) : 0;
  const danger = invert ? ratio > 0.8 : ratio > 0.8;
  const warn   = invert ? ratio > 0.5 : ratio > 0.5;
  const color  = danger ? "bg-[#ff5555]" : warn ? "bg-[#ffb86c]" : "bg-[#50fa7b]";

  return (
    <div className="p-4 rounded-xl border border-[#44475a] bg-[#282a36]">
      <div className="flex justify-between items-center mb-2">
        <span className="text-xs text-[#6272a4]">{label}</span>
        <span className={`text-xs font-semibold ${danger ? "text-[#ff5555]" : warn ? "text-[#ffb86c]" : "text-[#50fa7b]"}`}>
          {current.toFixed(2)}{unit} / {limit}{unit}
        </span>
      </div>
      <div className="h-2 rounded-full bg-[#44475a] overflow-hidden">
        <div className={`h-full rounded-full transition-all ${color}`} style={{ width: `${ratio * 100}%` }} />
      </div>
    </div>
  );
}

function LimitInput({ label, value, onChange, step = 0.5, min = 0, max = 100, suffix = "%" }: {
  label: string; value: number; onChange: (v: number) => void;
  step?: number; min?: number; max?: number; suffix?: string;
}) {
  return (
    <div>
      <label className="text-xs text-[#6272a4] mb-1 block">{label}</label>
      <div className="flex items-center gap-2">
        <input type="number" value={value} step={step} min={min} max={max}
          onChange={e => onChange(parseFloat(e.target.value))}
          className="flex-1 rounded-lg bg-[#282a36] border border-[#44475a] text-[#f8f8f2] text-sm px-3 py-2 focus:outline-none focus:ring-2 focus:ring-[#bd93f9]" />
        <span className="text-xs text-[#6272a4] w-6">{suffix}</span>
      </div>
    </div>
  );
}

export default function RiskPage() {
  const qc = useQueryClient();

  const { data: exposure, isLoading } = useQuery<RiskExposure>({
    queryKey: ["risk", "exposure"],
    queryFn: async () => {
      const r = await authFetch("/api/risk/exposure");
      if (!r.ok) throw new Error("노출도 조회 실패");
      return r.json();
    },
    refetchInterval: 15_000,
  });

  const [draft, setDraft] = useState<RiskLimits | null>(null);
  const limits = draft ?? exposure?.limits;

  const updateMutation = useMutation({
    mutationFn: async (payload: RiskLimits) => {
      const r = await authFetch("/api/risk/limits", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      if (!r.ok) throw new Error("한도 업데이트 실패");
      return r.json();
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["risk"] });
      setDraft(null);
    },
  });

  const won = (n: number) => n.toLocaleString("ko-KR");

  if (isLoading) return (
    <div className="max-w-3xl mx-auto px-4 py-8 space-y-3">
      {[1,2,3].map(i => <div key={i} className="h-16 rounded-xl bg-[#44475a]/30 animate-pulse" />)}
    </div>
  );

  return (
    <div className="max-w-3xl mx-auto px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-bold text-[#f8f8f2]">리스크 한도</h1>
        <p className="text-xs text-[#6272a4] mt-0.5">
          주문 전 자동 체크 — 한도 초과 시 주문이 거부됩니다
        </p>
      </div>

      {exposure && (
        <>
          {/* 총 자산 / 일일 P&L */}
          <div className="grid grid-cols-3 gap-3 mb-6">
            {[
              { label: "총 자산",    value: `${won(exposure.totalAssets)}원` },
              { label: "오늘 손익",
                value: `${exposure.dailyPnl >= 0 ? "+" : ""}${won(exposure.dailyPnl)}원`,
                color: exposure.dailyPnl >= 0 ? "text-[#50fa7b]" : "text-[#ff5555]" },
              { label: "미체결 주문", value: `${exposure.activeOrderCount}건` },
            ].map(c => (
              <div key={c.label} className="p-3 rounded-xl border border-[#44475a] bg-[#21222c]">
                <p className="text-xs text-[#6272a4] mb-1">{c.label}</p>
                <p className={`text-sm font-bold ${c.color ?? "text-[#f8f8f2]"}`}>{c.value}</p>
              </div>
            ))}
          </div>

          {/* 리스크 게이지 */}
          <div className="space-y-3 mb-6">
            <h2 className="text-sm font-semibold text-[#f8f8f2]">현재 리스크 수준</h2>

            <GaugeMeter label="일일 손실률"
              current={Math.abs(Math.min(exposure.dailyPnlPct, 0))}
              limit={exposure.limits.dailyLossLimitPct}
              invert />

            {exposure.topConcentration && (
              <GaugeMeter label={`최대 집중도 (${exposure.topConcentration.symbol})`}
                current={exposure.topConcentration.valuePct}
                limit={exposure.limits.concentrationLimitPct}
                invert />
            )}

            <GaugeMeter label="추정 VaR (95%)"
              current={exposure.estimatedVaR}
              limit={exposure.limits.varLimitPct}
              invert />

            <GaugeMeter label="1시간 주문 빈도"
              current={exposure.hourlyOrderCount}
              limit={exposure.limits.maxHourlyOrders}
              unit="회" invert />
          </div>
        </>
      )}

      {/* 한도 설정 */}
      {limits && (
        <div className="p-5 rounded-xl border border-[#44475a] bg-[#21222c] space-y-4">
          <h2 className="text-sm font-semibold text-[#f8f8f2]">한도 설정</h2>

          <div className="grid grid-cols-2 gap-4">
            <LimitInput label="일일 손실 한도 (%)"
              value={limits.dailyLossLimitPct}
              onChange={v => setDraft(d => ({ ...(d ?? limits), dailyLossLimitPct: v }))} />
            <LimitInput label="종목당 최대 비중 (%)"
              value={limits.concentrationLimitPct}
              onChange={v => setDraft(d => ({ ...(d ?? limits), concentrationLimitPct: v }))} />
            <LimitInput label="VaR 한도 (%)"
              value={limits.varLimitPct}
              onChange={v => setDraft(d => ({ ...(d ?? limits), varLimitPct: v }))} />
            <LimitInput label="최대 보유 종목 수"
              value={limits.maxPositionCount}
              onChange={v => setDraft(d => ({ ...(d ?? limits), maxPositionCount: v }))}
              step={1} suffix="개" />
            <LimitInput label="1시간 최대 주문 수"
              value={limits.maxHourlyOrders}
              onChange={v => setDraft(d => ({ ...(d ?? limits), maxHourlyOrders: v }))}
              step={1} suffix="회" />
            <div className="flex items-end">
              <label className="flex items-center gap-2 cursor-pointer">
                <input type="checkbox" checked={limits.isActive}
                  onChange={e => setDraft(d => ({ ...(d ?? limits), isActive: e.target.checked }))}
                  className="w-4 h-4 accent-[#bd93f9]" />
                <span className="text-sm text-[#f8f8f2]">리스크 체크 활성화</span>
              </label>
            </div>
          </div>

          {draft && (
            <div className="flex gap-2">
              <button onClick={() => updateMutation.mutate(draft)}
                disabled={updateMutation.isPending}
                className="flex-1 py-2.5 rounded-xl bg-[#bd93f9] text-[#282a36] font-bold text-sm hover:bg-[#ff79c6] transition-colors disabled:opacity-40">
                {updateMutation.isPending ? "저장 중..." : "한도 저장"}
              </button>
              <button onClick={() => setDraft(null)}
                className="px-4 py-2.5 rounded-xl border border-[#44475a] text-[#6272a4] text-sm hover:text-[#f8f8f2]">
                취소
              </button>
            </div>
          )}
        </div>
      )}

      <p className="mt-6 text-xs text-[#6272a4] text-center">
        한도는 모의투자 전용 리스크 게이트입니다. 실제 투자에는 적용되지 않습니다.
      </p>
    </div>
  );
}
