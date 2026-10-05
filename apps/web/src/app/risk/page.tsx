"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";
import { useToast } from "@/hooks/useToast";
import { usePaperPortfolio } from "@/hooks/usePaperTrade";
import { useRiskExposure, type RiskLimits } from "@/components/risk/useRiskExposure";
import { RiskGauges, RiskLevel, LEVELS, levelOf, type Gauge } from "@/components/risk/RiskGauges";
import { useStockMeta } from "@/components/portfolio/useStockMeta";
import { useSectorSlices } from "@/components/portfolio/Insights";
import { LoginRequired, Skeleton } from "@/components/portfolio/PaperStates";
import {
  Btn, DataTable, Field, Panel, PanelRow, PreviewTag, SelectBox, TerminalPage, Toggle, fmtNum, fmtPct, type TopStat,
} from "@/components/terminal";

type NumKey = "dailyLossLimitPct" | "concentrationLimitPct" | "varLimitPct" | "maxPositionCount" | "maxHourlyOrders";

const FIELDS: { key: NumKey; label: string; unit: string; step: number; int?: boolean; max?: number }[] = [
  { key: "varLimitPct", label: "1일 VaR 한도", unit: "%", step: 0.5, max: 100 },
  { key: "concentrationLimitPct", label: "단일 종목 최대 비중", unit: "%", step: 0.5, max: 100 },
  { key: "dailyLossLimitPct", label: "일일 최대 손실", unit: "%", step: 0.5, max: 100 },
  { key: "maxPositionCount", label: "최대 보유 종목 수", unit: "개", step: 1, int: true },
  { key: "maxHourlyOrders", label: "1시간 최대 주문 수", unit: "회", step: 1, int: true },
];

/** 저장 전 검증 — NaN·0·음수·소수 개수를 서버로 보내지 않는다 */
function validate(l: RiskLimits): string | null {
  for (const f of FIELDS) {
    const v = l[f.key];
    if (!Number.isFinite(v) || v <= 0) return `${f.label}은(는) 0보다 커야 합니다.`;
    if (f.int && !Number.isInteger(v)) return `${f.label}은(는) 정수여야 합니다.`;
    if (f.max != null && v > f.max) return `${f.label}은(는) ${f.max}${f.unit} 이하여야 합니다.`;
  }
  const sector = l.sectorConcentrationLimitPct;
  if (sector != null && (!Number.isFinite(sector) || sector <= 0 || sector > 100)) return "섹터 최대 비중은 0 초과 100 이하여야 합니다.";
  return null;
}

export default function RiskPage() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: exposure, isLoading, isError, refetch } = useRiskExposure(isLoggedIn);
  const { data: portfolio } = usePaperPortfolio();
  const holdings = useMemo(() => portfolio?.holdings ?? [], [portfolio]);
  const meta = useStockMeta(holdings.map((h) => h.stockId));
  const { slices } = useSectorSlices(holdings, portfolio?.cash ?? 0, meta);
  // 섹터 한도는 분류된 섹터에만 걸린다(미분류 종목은 대상이 아님 — ADR-069). 슬라이스는 비중 내림차순이다.
  const topSlice = slices.find((s) => s.name !== "현금" && s.name !== "미분류") ?? null;
  const topSector = topSlice?.name ?? null;
  const sectorPct = topSlice?.pct ?? null;

  const [draft, setDraft] = useState<RiskLimits | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const limits = draft ?? exposure?.limits;

  const updateMutation = useMutation({
    mutationFn: async (payload: RiskLimits) => {
      const r = await authFetch("/api/risk/limits", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...payload, clearSectorConcentrationLimit: payload.sectorConcentrationLimitPct == null }),
      });
      if (!r.ok) throw new Error("한도 업데이트 실패");
      return r.json();
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["risk"] });
      setDraft(null);
      toast({ type: "success", title: "한도를 저장했습니다" });
    },
    onError: (e) => toast({ type: "error", title: "한도 저장 실패", message: (e as Error).message }),
  });

  const save = () => {
    if (!draft) return;
    const err = validate(draft);
    setFormError(err);
    if (!err) updateMutation.mutate(draft);
  };

  const title = { title: "리스크 한도", crumb: "모의투자 · 주문 전 실시간 체크" };
  if (!isLoggedIn) {
    return (
      <TerminalPage {...title}>
        <LoginRequired message="리스크 한도를 보려면 로그인이 필요합니다." icon="shield" />
      </TerminalPage>
    );
  }

  const gauges: Gauge[] = [];
  let level = 0;
  if (exposure) {
    const L = exposure.limits;
    const varRatio = L.varLimitPct > 0 ? exposure.estimatedVaR / L.varLimitPct : 0;
    const varWon = (exposure.totalAssets * exposure.estimatedVaR) / 100;
    const conc = exposure.topConcentration;
    const concRatio = conc && L.concentrationLimitPct > 0 ? conc.valuePct / L.concentrationLimitPct : 0;
    const lossPct = Math.abs(Math.min(exposure.dailyPnlPct, 0));
    const lossRatio = L.dailyLossLimitPct > 0 ? lossPct / L.dailyLossLimitPct : 0;
    const hourRatio = L.maxHourlyOrders > 0 ? exposure.hourlyOrderCount / L.maxHourlyOrders : 0;
    const sectorRatio = L.sectorConcentrationLimitPct != null && L.sectorConcentrationLimitPct > 0 && sectorPct != null
      ? sectorPct / L.sectorConcentrationLimitPct : null;
    gauges.push(
      { name: "1일 VaR (95%)", value: `${exposure.estimatedVaR.toFixed(2)}%`, limit: `${L.varLimitPct}%`, ratio: varRatio, sub: `약 ${fmtNum(varWon)}원 — 하루에 이 이상 잃을 확률 5%` },
      { name: "단일 종목 집중도", value: conc ? `${conc.valuePct.toFixed(1)}%` : "—", limit: `${L.concentrationLimitPct}%`, ratio: concRatio, sub: conc ? conc.symbol : "보유 종목 없음" },
      {
        name: "섹터 집중도",
        value: sectorPct != null ? `${sectorPct.toFixed(1)}%` : "—",
        limit: L.sectorConcentrationLimitPct != null ? `${L.sectorConcentrationLimitPct}%` : "미설정",
        ratio: sectorRatio,
        sub: topSector ? (L.sectorConcentrationLimitPct != null ? topSector : `${topSector} — 한도를 설정하면 매수 때 함께 점검합니다`) : "분류된 섹터 보유 없음",
      },
      { name: "일일 손실", value: fmtPct(exposure.dailyPnlPct), limit: `-${L.dailyLossLimitPct}%`, ratio: lossRatio, sub: "오늘 실현+평가 손익 기준" },
      { name: "1시간 주문 빈도", value: `${exposure.hourlyOrderCount}회`, limit: `${L.maxHourlyOrders}회`, ratio: hourRatio, sub: `미체결 주문 ${exposure.activeOrderCount}건` },
    );
    level = Math.max(...[varRatio, concRatio, lossRatio, hourRatio, sectorRatio ?? 0].map(levelOf));
  }
  const varUse = exposure && exposure.limits.varLimitPct > 0 ? (exposure.estimatedVaR / exposure.limits.varLimitPct) * 100 : null;

  const stats: TopStat[] = exposure
    ? [
        { label: "리스크 수준", value: LEVELS[level].name, tone: LEVELS[level].text },
        { label: "VaR 사용", value: varUse != null ? `${varUse.toFixed(0)}%` : "—" },
        { label: "섹터 최대", value: topSector && sectorPct != null ? `${topSector} ${sectorPct.toFixed(0)}%` : "—" },
        { label: "이번 달 차단", value: "—", tone: "text-tm-muted" },
      ]
    : [];

  const set = (k: NumKey, v: number) => limits && setDraft({ ...limits, [k]: v });

  return (
    <TerminalPage {...title} stats={stats}>
      <Panel tabs={["현재 리스크"]} actions={["refresh", "expand"]} onAction={(a) => a === "refresh" && refetch()}>
        {isLoading ? (
          <Skeleton className="h-40" />
        ) : isError || !exposure ? (
          <p role="alert" className="m-0 py-8 text-center text-13 text-[#ff8a8a]">리스크 노출도를 불러오지 못했습니다.</p>
        ) : (
          <>
            <RiskLevel level={level} />
            <RiskGauges gauges={gauges} />
          </>
        )}
      </Panel>

      <PanelRow>
        <Panel tabs={["차단·경고 기록"]} actions={[]} preview className="flex-[999_1_600px]" bodyClassName="px-1.5 pb-1.5 pt-1">
          {/* 리스크 게이트 판정 이력 API가 아직 없다 — 표 머리만 시안대로 두고 비워 둔다 */}
          <DataTable
            columns={[
              { key: "t", header: "시각", cell: () => null },
              { key: "o", header: "주문", cell: () => null },
              { key: "r", header: "사유", cell: () => null },
              { key: "x", header: "결과", cell: () => null },
            ]}
            rows={[]}
            rowKey={(_, i) => i}
            minWidth={640}
            empty="차단·경고 기록은 준비 중입니다. 지금은 주문 화면에서 거부 사유를 바로 보여 줍니다."
          />
        </Panel>

        <Panel tabs={["한도 설정"]} actions={[]} className="flex-[1_1_340px]">
          {!limits ? (
            <Skeleton className="h-64" />
          ) : (
            <>
              <div className="flex items-center justify-between gap-3">
                <span className="flex items-center gap-1.5 font-semibold">리스크 체크 활성화 <PreviewTag /></span>
                {/* PUT /api/risk/limits가 isActive를 받지 않는다 — 현재 값만 보여 준다 */}
                <Toggle checked={limits.isActive} label="리스크 체크" disabled />
              </div>
              {FIELDS.slice(0, 2).map((f) => (
                <Field key={f.key} label={f.label} aria-label={f.label} unit={f.unit} type="number" step={f.step} min={0} max={f.max} value={Number.isFinite(limits[f.key]) ? limits[f.key] : ""} onChange={(e) => set(f.key, parseFloat(e.target.value))} className="flex-none" />
              ))}
              <Field
                label="섹터 최대 비중 (비우면 미설정)"
                aria-label="섹터 최대 비중"
                unit="%"
                type="number"
                step={0.5}
                min={0}
                max={100}
                placeholder="미설정"
                value={limits.sectorConcentrationLimitPct != null && Number.isFinite(limits.sectorConcentrationLimitPct) ? limits.sectorConcentrationLimitPct : ""}
                onChange={(e) => setDraft({ ...limits, sectorConcentrationLimitPct: e.target.value === "" ? null : parseFloat(e.target.value) })}
                className="flex-none"
              />
              {FIELDS.slice(2).map((f) => (
                <Field key={f.key} label={f.label} aria-label={f.label} unit={f.unit} type="number" step={f.step} min={0} max={f.max} value={Number.isFinite(limits[f.key]) ? limits[f.key] : ""} onChange={(e) => set(f.key, parseFloat(e.target.value))} className="flex-none" />
              ))}
              <SelectBox label="한도 초과 시" value="BLOCK" disabled className="opacity-70">
                <option value="BLOCK">주문 차단</option>
              </SelectBox>
              {formError && <p role="alert" className="m-0 text-xs text-[#ff8a8a]">{formError}</p>}
              <div className="flex gap-2">
                <Btn kind="primary" size="lg" className="flex-1" onClick={save} disabled={!draft || updateMutation.isPending}>
                  {updateMutation.isPending ? "저장 중..." : "한도 저장"}
                </Btn>
                {draft && <Btn kind="ghost" size="lg" onClick={() => { setDraft(null); setFormError(null); }}>취소</Btn>}
              </div>
              <span className="text-2xs text-tm-muted">한도는 모의투자 전용 리스크 게이트입니다. 한도를 넘는 주문은 체결되지 않습니다. 실제 투자에는 적용되지 않습니다.</span>
            </>
          )}
        </Panel>
      </PanelRow>
    </TerminalPage>
  );
}
