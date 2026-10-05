"use client";

import { useState, useEffect, useMemo, type ReactNode } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { RuleCondition, RuleOperator, RuleSet } from "@monticker/types";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import {
  AutoGrid, Btn, BtnLink, Divider, Field, H2, Icon, LineChart, Notice, Panel, PanelCol, PanelRow,
  PreviewTag, Stat, TerminalPage, TitleBlock, fmtNum, fmtPct, type IconName,
} from "@/components/terminal";
import { SegOpts, fmtMdd, rulesetStatus } from "@/components/quant/parts";
import { ChipNumber, ChipSelect, CondShell, JoinTag, NumField } from "@/components/quant/builderParts";
import { useRuleSetBacktests } from "@/components/quant/RuleSetCard";

// ── 상수 ───────────────────────────────────────────────────────────────────────

const INDICATORS = [
  { value: "CLOSE_VS_MA",    label: "현재가 vs 이동평균",   params: ["period"], comparators: ["GT","LT"] },
  { value: "VOLUME_RATIO",   label: "거래량 배율",           params: ["period"], comparators: ["GT","LT"], hasValue: true },
  { value: "RSI",            label: "RSI",                   params: ["period"], comparators: ["GT","LT","BETWEEN"], hasValue: true },
  { value: "MACD_CROSS",     label: "MACD 크로스",           params: [],         comparators: ["GOLDEN","DEAD"] },
  { value: "PRICE_CHANGE",   label: "N일 가격변화율(%)",     params: ["period"], comparators: ["GT","LT"], hasValue: true },
  { value: "BOLLINGER_BAND", label: "볼린저밴드",            params: ["period"], comparators: ["ABOVE_UPPER","BELOW_LOWER"] },
  // ADR-079 — 보조 데이터 지표. 장 마감(15:30) 이후 나온 뉴스·공시는 다음 거래일부터 반영된다.
  { value: "NEWS_SENTIMENT", label: "뉴스 감성(-1~1)",       params: ["period"], comparators: ["GT","LT"], hasValue: true, step: 0.1, defaultPeriod: 5 },
  { value: "DISCLOSURE",     label: "공시 발생",             params: ["period"], comparators: ["ANY","EARNINGS","BUYBACK","RIGHTS_ISSUE","BONUS_ISSUE","MNA","INSIDER","DIVIDEND"], defaultPeriod: 5 },
  { value: "PROFIT_RATE",    label: "수익률(%)",             params: [],         comparators: ["GTE","LTE"], hasValue: true, exitOnly: true },
  { value: "LOSS_RATE",      label: "손실률(%)",             params: [],         comparators: ["LTE"],       hasValue: true, exitOnly: true },
] as const;

const COMPARATOR_LABEL: Record<string, string> = {
  GT: ">", LT: "<", GTE: "≥", LTE: "≤",
  BETWEEN: "사이", GOLDEN: "골든크로스", DEAD: "데드크로스",
  ABOVE_UPPER: "상단 돌파", BELOW_LOWER: "하단 이탈",
  ANY: "모든 공시", EARNINGS: "실적·정기보고서", BUYBACK: "자사주 취득", RIGHTS_ISSUE: "유상증자",
  BONUS_ISSUE: "무상증자", MNA: "합병·분할·인수", INSIDER: "임원·주요주주 지분", DIVIDEND: "배당 결정",
};

const UNIVERSE_MARKETS = [
  { key: "all", label: "전체" },
  { key: "domestic", label: "국내" },
  { key: "overseas", label: "해외" },
];
const UNIVERSE_MARKET_CAP_TIERS = [
  { key: "all", label: "시총 전체" },
  { key: "large", label: "대형주" },
  { key: "mid", label: "중형주" },
  { key: "small", label: "소형주" },
];

interface Condition extends RuleCondition {
  id: string;
  params: Record<string, number>;
}

const DEFAULT_ENTRY: Condition[] = [
  { id: "e1", indicator: "CLOSE_VS_MA",  comparator: "GT",      params: { period: 20 } },
  { id: "e2", indicator: "VOLUME_RATIO", comparator: "GT",      params: { period: 20 }, value: 2 },
  { id: "e3", indicator: "RSI",          comparator: "BETWEEN", params: { period: 14 }, value: [30, 70] },
];
const DEFAULT_EXIT: Condition[] = [
  { id: "x1", indicator: "PROFIT_RATE", comparator: "GTE", params: {}, value: 8 },
  { id: "x2", indicator: "LOSS_RATE",   comparator: "LTE", params: {}, value: -4 },
];

/** 조건 블록 팔레트 — 누르면 매수 조건에 추가된다. 엔진에 없는 지표는 이유와 함께 막아 둔다. */
const BLOCKS: { label: string; icon: IconName; make?: () => Omit<Condition, "id">; reason?: string }[] = [
  { label: "거래량",     icon: "filter",  make: () => ({ indicator: "VOLUME_RATIO",   comparator: "GT", params: { period: 20 }, value: 2 }) },
  { label: "가격 변동",  icon: "trend",   make: () => ({ indicator: "PRICE_CHANGE",   comparator: "GT", params: { period: 5 },  value: 3 }) },
  { label: "이동평균",   icon: "line",    make: () => ({ indicator: "CLOSE_VS_MA",    comparator: "GT", params: { period: 20 } }) },
  { label: "RSI",        icon: "bars",    make: () => ({ indicator: "RSI",            comparator: "LT", params: { period: 14 }, value: 30 }) },
  { label: "MACD",       icon: "compare", make: () => ({ indicator: "MACD_CROSS",     comparator: "GOLDEN", params: {} }) },
  { label: "볼린저밴드", icon: "hlines",  make: () => ({ indicator: "BOLLINGER_BAND", comparator: "BELOW_LOWER", params: { period: 20 } }) },
  { label: "뉴스 감성",  icon: "news",    make: () => ({ indicator: "NEWS_SENTIMENT", comparator: "GT", params: { period: 5 }, value: 0.3 }) },
  { label: "공시 유형",  icon: "doc",     make: () => ({ indicator: "DISCLOSURE",     comparator: "BUYBACK", params: { period: 5 } }) },
  { label: "배당 공시",  icon: "card",    make: () => ({ indicator: "DISCLOSURE",     comparator: "DIVIDEND", params: { period: 5 } }) },
  // 시가총액은 종목당 최신 값 1개뿐이라 과거 시점으로 백테스트하면 미래 정보가 섞인다. 종목군은 아래 유니버스에서 고른다.
  { label: "시가총액",   icon: "pie",     reason: "시가총액 이력이 없어 조건으로 쓸 수 없습니다 — 유니버스의 시총 필터를 쓰세요" },
];

const COND_COLORS = ["text-dracula-purple", "text-dracula-cyan", "text-dracula-pink", "text-dracula-orange", "text-dracula-green"];

interface ParsedRuleDefinition {
  entryRules?: { operator?: RuleOperator; conditions?: RuleCondition[] };
  exitRules?: { operator?: RuleOperator; conditions?: RuleCondition[] };
  positionSizing?: { value?: number };
  hardExits?: { maxHoldDays?: number; trailingStopPct?: number };
}

function uid() { return Math.random().toString(36).slice(2, 8); }

function indicatorHasValue(meta: (typeof INDICATORS)[number] | undefined): boolean {
  return !!meta && "hasValue" in meta && meta.hasValue;
}

// ── 조건을 human-readable 한 줄로 변환 ────────────────────────────────────────

function condToText(c: Condition): string {
  const meta = INDICATORS.find(i => i.value === c.indicator);
  const label = meta?.label ?? c.indicator;
  const period = c.params?.period;
  const periodStr = period ? `(${period})` : "";
  const cmpLabel = COMPARATOR_LABEL[c.comparator] ?? c.comparator;

  if (c.indicator === "DISCLOSURE") return `최근 ${period ?? 5}거래일 ${cmpLabel} 공시`;
  if (c.comparator === "GOLDEN") return `${label} — 골든크로스`;
  if (c.comparator === "DEAD")   return `${label} — 데드크로스`;
  if (c.comparator === "ABOVE_UPPER") return `${label}${periodStr} 상단 돌파`;
  if (c.comparator === "BELOW_LOWER") return `${label}${periodStr} 하단 이탈`;
  if (c.comparator === "BETWEEN") {
    const lo = Array.isArray(c.value) ? c.value[0] : "?";
    const hi = Array.isArray(c.value) ? c.value[1] : "?";
    return `${label}${periodStr}  ${lo} ~ ${hi}`;
  }
  const val = typeof c.value === "number" ? c.value : "";
  return `${label}${periodStr}  ${cmpLabel} ${val}`;
}

// ── 조건 행 — 시안 cond(): [지표] [기간] [비교] [값] × ─────────────────────────

function ConditionRow({ cond, color, onChange, onRemove, exitMode }: {
  cond: Condition;
  color: string;
  onChange: (c: Condition) => void;
  onRemove: () => void;
  exitMode?: boolean;
}) {
  const meta = INDICATORS.find(i => i.value === cond.indicator);
  const options = INDICATORS.filter(i => exitMode || !("exitOnly" in i && i.exitOnly));

  return (
    <CondShell onRemove={onRemove}>
      <ChipSelect
        aria-label="지표"
        colorClass={color}
        value={cond.indicator}
        onChange={e => {
          const m = INDICATORS.find(i => i.value === e.target.value)!;
          // 기간을 비워 두면 화면(20)과 서버 기본값이 어긋날 수 있다 — 고를 때 명시적으로 채운다.
          const period = (m.params as readonly string[]).includes("period") ? ("defaultPeriod" in m ? m.defaultPeriod : 20) : undefined;
          onChange({ ...cond, indicator: e.target.value, comparator: m.comparators[0], params: period ? { period } : {}, value: undefined });
        }}
      >
        {options.map(i => <option key={i.value} value={i.value}>{i.label}</option>)}
      </ChipSelect>

      {(meta?.params as readonly string[] | undefined)?.includes("period") && (
        <ChipNumber
          aria-label="기간"
          suffix="일"
          value={cond.params.period ?? 20}
          onChange={e => onChange({ ...cond, params: { ...cond.params, period: +e.target.value } })}
          min={1}
        />
      )}

      <ChipSelect aria-label="비교" value={cond.comparator} onChange={e => onChange({ ...cond, comparator: e.target.value })}>
        {meta?.comparators.map(c => (
          <option key={c} value={c}>{COMPARATOR_LABEL[c] ?? c}</option>
        ))}
      </ChipSelect>

      {indicatorHasValue(meta) && cond.comparator === "BETWEEN" ? (
        <>
          <ChipNumber
            aria-label="하한"
            value={Array.isArray(cond.value) ? cond.value[0] : 30}
            onChange={e => onChange({ ...cond, value: [+e.target.value, Array.isArray(cond.value) ? cond.value[1] : 70] })}
          />
          <span className="text-xs text-tm-muted">~</span>
          <ChipNumber
            aria-label="상한"
            value={Array.isArray(cond.value) ? cond.value[1] : 70}
            onChange={e => onChange({ ...cond, value: [Array.isArray(cond.value) ? cond.value[0] : 30, +e.target.value] })}
          />
        </>
      ) : indicatorHasValue(meta) ? (
        <ChipNumber
          aria-label="값"
          step={meta && "step" in meta ? meta.step : undefined}
          value={typeof cond.value === "number" ? cond.value : ""}
          onChange={e => onChange({ ...cond, value: +e.target.value })}
        />
      ) : null}
    </CondShell>
  );
}

const OP_OPTIONS = [
  { value: "AND" as const, label: "모두 충족 시" },
  { value: "OR" as const, label: "하나라도 충족 시" },
];

// ── 메인 페이지 ───────────────────────────────────────────────────────────────

export default function BuilderPage() {
  const router = useRouter();
  const params = useSearchParams();
  const editId = params.get("edit");
  const qc = useQueryClient();
  const { toast } = useToast();

  const [name,                   setName]                   = useState("");
  const [description,            setDescription]            = useState("");
  const [entryOp,                setEntryOp]                = useState<"AND" | "OR">("AND");
  const [exitOp,                 setExitOp]                 = useState<"AND" | "OR">("OR");
  const [entry,                  setEntry]                  = useState<Condition[]>(DEFAULT_ENTRY);
  const [exit,                   setExit]                   = useState<Condition[]>(DEFAULT_EXIT);
  const [positionPct,            setPositionPct]            = useState(10);
  const [maxHoldDays,            setMaxHoldDays]            = useState<number | null>(null);
  const [trailingPct,            setTrailingPct]            = useState<number | null>(null);
  const [universeMarket,         setUniverseMarket]         = useState("all");
  const [universeMarketCapTier,  setUniverseMarketCapTier]  = useState("all");
  const [blockQuery,             setBlockQuery]             = useState("");

  const { data: existing } = useQuery<RuleSet>({
    queryKey: ["quant", "ruleset", editId],
    queryFn: async () => {
      const res = await authFetch(`/api/quant/rulesets/${editId}`);
      if (!res.ok) throw new Error("룰셋 조회 실패");
      return res.json();
    },
    enabled: !!editId,
  });

  const { data: backtests, refetch: refetchBacktests, isFetching: btFetching } = useRuleSetBacktests(editId);
  const latest = backtests?.[0];

  useEffect(() => {
    if (!existing) return;
    setName(existing.name);
    setDescription(existing.description ?? "");
    try {
      const def: ParsedRuleDefinition = JSON.parse(existing.ruleDefinition ?? "{}");
      setEntryOp(def.entryRules?.operator ?? "AND");
      setExitOp(def.exitRules?.operator ?? "OR");
      setEntry(def.entryRules?.conditions?.map((c, i) => ({ ...c, id: `e${i}`, params: (c as Condition).params ?? {} })) ?? []);
      setExit(def.exitRules?.conditions?.map((c, i) => ({ ...c, id: `x${i}`, params: (c as Condition).params ?? {} })) ?? []);
      setPositionPct(def.positionSizing?.value ?? 10);
      setMaxHoldDays(def.hardExits?.maxHoldDays ?? null);
      setTrailingPct(def.hardExits?.trailingStopPct ?? null);
    } catch {}
    try {
      const universe: { market?: string; marketCapTier?: string } = JSON.parse(existing.universeJson || "{}");
      setUniverseMarket(universe.market ?? "all");
      setUniverseMarketCapTier(universe.marketCapTier ?? "all");
    } catch {}
  }, [existing]);

  const buildPayload = () => ({
    name,
    description: description || null,
    ruleDefinition: {
      entryRules:    { operator: entryOp, conditions: entry.map(({ id: _id, ...c }) => c) },
      exitRules:     { operator: exitOp,  conditions: exit.map(({ id: _id, ...c }) => c) },
      positionSizing: { type: "FIXED_RATIO", value: positionPct },
      // 비워 두면 보내지 않는다 — 기존 룰셋의 지문(fingerprint)이 괜히 바뀌지 않게.
      ...(maxHoldDays != null || trailingPct != null
        ? { hardExits: { ...(maxHoldDays != null && { maxHoldDays }), ...(trailingPct != null && { trailingStopPct: trailingPct }) } }
        : {}),
    },
    universeJson: { market: universeMarket, marketCapTier: universeMarketCapTier },
  });

  const saveMutation = useMutation({
    mutationFn: async () => {
      const payload = buildPayload();
      const url = editId ? `/api/quant/rulesets/${editId}` : "/api/quant/rulesets";
      const res = await authFetch(url, {
        method: editId ? "PUT" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      if (!res.ok) throw new Error("저장 실패");
      return res.json();
    },
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ["quant", "rulesets"] });
      toast({ type: "success", title: "저장 완료", message: `"${data.name}" 룰셋이 저장되었습니다.` });
      router.push(`/quant-lab/${data.id}`);
    },
    onError: () => toast({ type: "error", title: "저장 실패", message: "다시 시도해주세요." }),
  });

  const maxHoldValid = maxHoldDays == null || (Number.isInteger(maxHoldDays) && maxHoldDays >= 1 && maxHoldDays <= 500);
  const trailingValid = trailingPct == null || (trailingPct > 0 && trailingPct <= 50);
  // 강제 청산(최대 보유·트레일링)만으로도 청산될 수 있으니 매도 조건 대신 쓸 수 있다.
  const hasExit = exit.length > 0 || maxHoldDays != null || trailingPct != null;
  const canSave = name.trim().length > 0 && entry.length > 0 && hasExit && maxHoldValid && trailingValid;

  const addEntry = () => setEntry(p => [...p, { id: uid(), indicator: "CLOSE_VS_MA",  comparator: "GT",  params: { period: 20 } }]);
  const addExit  = () => setExit(p => [...p,  { id: uid(), indicator: "CLOSE_VS_MA",  comparator: "LT",  params: { period: 20 } }]);

  // 손절·익절 칸은 첫 LOSS_RATE / PROFIT_RATE 조건에 묶는다. 나머지 청산 조건은 아래 목록으로.
  const lossCond = exit.find(c => c.indicator === "LOSS_RATE");
  const profitCond = exit.find(c => c.indicator === "PROFIT_RATE");
  const otherExits = exit.filter(c => c !== lossCond && c !== profitCond);
  const setBound = (indicator: "LOSS_RATE" | "PROFIT_RATE", v: number | null) => {
    setExit(p => {
      const cur = p.find(c => c.indicator === indicator);
      if (v == null) return cur ? p.filter(c => c !== cur) : p;
      if (cur) return p.map(c => (c === cur ? { ...c, value: v } : c));
      return [...p, { id: uid(), indicator, comparator: indicator === "LOSS_RATE" ? "LTE" : "GTE", params: {}, value: v }];
    });
  };

  const universeLabel = [
    UNIVERSE_MARKETS.find(m => m.key === universeMarket)?.label ?? "전체",
    universeMarket !== "overseas"
      ? UNIVERSE_MARKET_CAP_TIERS.find(t => t.key === universeMarketCapTier)?.label
      : undefined,
  ].filter(Boolean).join(" · ");

  const blocks = BLOCKS.filter(b => b.label.toLowerCase().includes(blockQuery.trim().toLowerCase()));
  const st = existing ? rulesetStatus(existing.status) : null;
  const reliabilityTone = latest?.reliabilityScore === "A" || latest?.reliabilityScore === "B" ? "text-dracula-green" : latest?.reliabilityScore ? "text-dracula-orange" : "text-tm-muted";
  const lastSaved = existing ? new Date(existing.updatedAt) : null;
  const curve = useMemo(() => latest?.equityCurve.map(p => p.equity) ?? [], [latest]);

  // ── 전략 미리보기 문장 ──────────────────────────────────────────────────────
  const joinNodes = (conds: Condition[], sep: string, offset = 0): ReactNode[] =>
    conds.flatMap((c, i) => [
      i > 0 ? <span key={`s${c.id}`}>{sep}</span> : null,
      <span key={c.id} className={COND_COLORS[(i + offset) % COND_COLORS.length]}>{condToText(c)}</span>,
    ]);

  return (
    <TerminalPage
      left={
        <TitleBlock
          crumb="퀀트랩 / 룰셋 빌더"
          title={<>{name.trim() || (editId ? "룰셋 수정" : "새 룰셋")}{existing && <> <span className="num text-dracula-purple">v{existing.version}</span></>}</>}
        />
      }
      stats={[
        { label: "상태", value: st?.label ?? "작성 중", tone: existing?.status === "RUNNING" ? "text-dracula-green" : undefined },
        { label: "마지막 저장", value: lastSaved ? `${String(lastSaved.getMonth() + 1).padStart(2, "0")}.${String(lastSaved.getDate()).padStart(2, "0")} ${String(lastSaved.getHours()).padStart(2, "0")}:${String(lastSaved.getMinutes()).padStart(2, "0")}` : "—", tone: lastSaved ? undefined : "text-tm-muted" },
        { label: "조건", value: `진입 ${entry.length} · 청산 ${exit.length}` },
        { label: "검증", value: latest?.reliabilityScore ? `신뢰도 ${latest.reliabilityScore}` : "—", tone: reliabilityTone },
      ]}
    >
      <PanelRow>
        {/* ── 조건 블록 팔레트 ─────────────────────────────── */}
        <Panel tabs={["조건 블록"]} actions={[]} closable={false} className="flex-[0_1_260px]">
          <label className="flex h-9 items-center gap-2 rounded-lg bg-tm-inner px-2.5 text-tm-muted">
            <Icon name="search" size={15} />
            <input
              type="search"
              aria-label="지표 검색"
              placeholder="지표 검색"
              value={blockQuery}
              onChange={e => setBlockQuery(e.target.value)}
              className="min-w-0 flex-1 bg-transparent text-13 text-dracula-fg outline-none"
            />
          </label>
          <div className="grid grid-cols-2 gap-1.5">
            {blocks.map(b => (
              <button
                key={b.label}
                type="button"
                disabled={!b.make}
                title={b.make ? `${b.label} 조건을 매수 조건에 추가` : b.reason ?? "준비 중인 지표입니다"}
                onClick={() => b.make && setEntry(p => [...p, { id: uid(), ...b.make!() }])}
                className="flex h-[38px] items-center gap-2 rounded-lg border border-tm-line bg-tm-inner px-2.5 text-left text-13 text-tm-soft hover:border-tm-line2 hover:text-dracula-fg disabled:cursor-not-allowed disabled:opacity-50"
              >
                <Icon name={b.icon} size={15} />
                <span className="min-w-0 truncate">{b.label}</span>
              </button>
            ))}
          </div>
          <span className="text-2xs text-tm-muted">블록을 누르면 오른쪽 매수 조건에 추가됩니다. 뉴스·공시는 장 마감 뒤 나온 것을 다음 거래일부터 반영합니다. 흐린 블록은 이유를 마우스를 올려 확인하세요.</span>
        </Panel>

        {/* ── 캔버스 ─────────────────────────────────────── */}
        <Panel tabs={[name.trim() || "새 전략"]} actions={[]} className="flex-[999_1_520px]">
          <div className="flex flex-wrap gap-2">
            <Field label="전략 이름" mono={false} placeholder="예: 거래량 돌파 단기 전략" value={name} onChange={e => setName(e.target.value)} className="flex-[2_1_220px]" />
            <Field label="전략 설명 (선택)" mono={false} placeholder="한 줄 설명" value={description} onChange={e => setDescription(e.target.value)} className="flex-[3_1_260px]" />
          </div>
          <Divider />

          <H2 sub={<SegOpts label="매수 조건 결합" value={entryOp} onChange={setEntryOp} options={OP_OPTIONS} />}>매수 조건</H2>
          {entry.length === 0 && <p className="m-0 text-13 text-tm-muted">매수 조건이 없습니다 — 왼쪽 블록을 누르거나 조건을 추가하세요.</p>}
          {entry.map((c, i) => (
            <div key={c.id} className="flex flex-col gap-3">
              {i > 0 && <JoinTag op={entryOp} />}
              <ConditionRow
                cond={c}
                color={COND_COLORS[i % COND_COLORS.length]}
                onChange={nc => setEntry(p => p.map(x => x.id === nc.id ? nc : x))}
                onRemove={() => setEntry(p => p.filter(x => x.id !== c.id))}
              />
            </div>
          ))}
          <button type="button" onClick={addEntry} className="h-10 rounded-lg border border-dashed border-tm-line2 text-13 text-tm-soft hover:text-dracula-fg">
            + 조건 추가
          </button>
          <Divider />

          <H2 sub={<SegOpts label="매도 조건 결합" value={exitOp} onChange={setExitOp} options={OP_OPTIONS} />}>매도 조건</H2>
          <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(140px,1fr))" }}>
            <NumField label="손절" unit="%" value={typeof lossCond?.value === "number" ? lossCond.value : null} onCommit={v => setBound("LOSS_RATE", v)} placeholder="예: -4" inputClassName="text-down" />
            <NumField label="익절" unit="%" value={typeof profitCond?.value === "number" ? profitCond.value : null} onCommit={v => setBound("PROFIT_RATE", v)} placeholder="예: 8" inputClassName="text-up" />
            <NumField label="최대 보유" unit="거래일" value={maxHoldDays} onCommit={setMaxHoldDays} placeholder="예: 20" inputMode="numeric" />
            <NumField label="트레일링" unit="%" value={trailingPct} onCommit={setTrailingPct} placeholder="예: 7" inputClassName="text-down" />
          </div>
          {otherExits.map((c, i) => (
            <div key={c.id} className="flex flex-col gap-3">
              {i > 0 && <JoinTag op={exitOp} />}
              <ConditionRow
                exitMode
                cond={c}
                color={COND_COLORS[(i + 2) % COND_COLORS.length]}
                onChange={nc => setExit(p => p.map(x => x.id === nc.id ? nc : x))}
                onRemove={() => setExit(p => p.filter(x => x.id !== c.id))}
              />
            </div>
          ))}
          <button type="button" onClick={addExit} className="h-10 rounded-lg border border-dashed border-tm-line2 text-13 text-tm-soft hover:text-dracula-fg">
            + 청산 조건 추가
          </button>
          <Divider />

          <H2>포지션 사이징</H2>
          <div className="flex flex-wrap items-center gap-2">
            <SegOpts
              label="사이징 방식"
              size="lg"
              value="FIXED"
              options={[
                { value: "FIXED", label: "고정 비중" },
                { value: "VOL", label: "변동성 역가중", disabled: true },
                { value: "KELLY", label: "켈리 1/2", disabled: true },
              ]}
            />
            <Field label="1회 투입" unit="% / 계좌" type="number" min={1} max={100} value={positionPct} onChange={e => setPositionPct(+e.target.value)} className="flex-[1_1_140px]" />
            <Field label="최대 동시 보유" unit="종목" placeholder="준비 중" disabled className="flex-[1_1_140px] opacity-60" />
          </div>
          <Divider />

          <H2 sub="이 전략이 대상으로 하는 종목군">유니버스</H2>
          <div className="flex flex-wrap items-center gap-1.5">
            <ChipSelect
              aria-label="시장"
              value={universeMarket}
              onChange={e => {
                setUniverseMarket(e.target.value);
                if (e.target.value === "overseas") setUniverseMarketCapTier("all");
              }}
              className="h-[30px] rounded-full border-0 bg-tm-raised"
              colorClass="text-tm-soft"
            >
              {UNIVERSE_MARKETS.map(m => <option key={m.key} value={m.key}>시장 · {m.label}</option>)}
            </ChipSelect>
            {universeMarket !== "overseas" && (
              <ChipSelect
                aria-label="시가총액"
                value={universeMarketCapTier}
                onChange={e => setUniverseMarketCapTier(e.target.value)}
                className="h-[30px] rounded-full border-0 bg-tm-raised"
                colorClass="text-tm-soft"
              >
                {UNIVERSE_MARKET_CAP_TIERS.map(t => <option key={t.key} value={t.key}>{t.label}</option>)}
              </ChipSelect>
            )}
            <button type="button" disabled title="준비 중인 기능입니다" className="inline-flex h-[30px] cursor-not-allowed items-center gap-1.5 rounded-full border border-tm-line2 px-2.5 text-xs text-tm-muted">
              + 필터 <PreviewTag />
            </button>
          </div>
        </Panel>

        {/* ── 오른쪽: 미리보기 + 빠른 백테스트 ───────────────── */}
        <PanelCol className="flex-[1_1_360px]">
          <Panel tabs={["전략 미리보기"]} actions={[]} closable={false}>
            <p className="m-0 text-sm leading-[1.75] text-tm-soft">
              <b className="text-dracula-fg">{universeLabel}</b> 종목에서,{" "}
              {entry.length === 0 ? <span className="text-tm-muted">(매수 조건 없음)</span> : joinNodes(entry, entryOp === "AND" ? " 그리고 " : " 또는 ")}
              {entry.length > 1 ? (entryOp === "AND" ? " 조건을 모두 충족하면" : " 중 하나라도 충족하면") : " 이면"}{" "}
              계좌의 <span className="num text-dracula-fg">{positionPct}%</span>로 매수합니다.{" "}
              {exit.length === 0 ? <span className="text-tm-muted">(매도 조건 없음)</span> : joinNodes(exit, ", ", 2)}
              {exit.length > 1 ? (exitOp === "OR" ? " 중 하나라도 충족하면" : " 을 모두 충족하면") : " 이면"} 청산합니다.
              {(maxHoldDays != null || trailingPct != null) && (
                <>
                  {" "}또한{" "}
                  {maxHoldDays != null && <span className="num text-dracula-cyan">{maxHoldDays}거래일 보유</span>}
                  {maxHoldDays != null && trailingPct != null && " 또는 "}
                  {trailingPct != null && <span className="num text-dracula-pink">고점 대비 -{trailingPct}%</span>}
                  {" "}에 도달하면 조건과 관계없이 청산합니다.
                </>
              )}
            </p>
            {!canSave && (
              <Notice tone="warn">
                {!name.trim() && <div>· 전략 이름을 입력하세요</div>}
                {entry.length === 0 && <div>· 매수 조건을 1개 이상 추가하세요</div>}
                {!hasExit && <div>· 매도 조건이나 최대 보유·트레일링을 1개 이상 정하세요</div>}
                {!maxHoldValid && <div>· 최대 보유는 1~500 거래일 정수여야 합니다</div>}
                {!trailingValid && <div>· 트레일링은 0% 초과 50% 이하여야 합니다</div>}
              </Notice>
            )}
            <Btn icon="check" full size="lg" onClick={() => saveMutation.mutate()} disabled={!canSave || saveMutation.isPending}>
              {saveMutation.isPending ? "저장 중..." : editId ? "룰셋 업데이트" : "룰셋 저장"}
            </Btn>
            <span className="text-center text-2xs text-tm-muted">저장 후 바로 백테스트 화면으로 이동합니다</span>
          </Panel>

          <Panel
            tabs={["빠른 백테스트"]}
            actions={editId ? ["refresh"] : []}
            onAction={a => a === "refresh" && refetchBacktests()}
            right={latest ? <span className="num text-2xs text-tm-muted">{latest.startDate} ~ {latest.endDate}</span> : undefined}
          >
            {!editId ? (
              <p className="m-0 py-4 text-center text-13 text-tm-muted">룰셋을 저장하면 백테스트를 실행할 수 있습니다.</p>
            ) : !latest ? (
              <p className="m-0 py-4 text-center text-13 text-tm-muted">{btFetching ? "불러오는 중…" : "아직 백테스트 결과가 없습니다."}</p>
            ) : (
              <>
                <AutoGrid min={110}>
                  <Stat big label="CAGR" value={fmtPct(latest.annualReturn, 1)} valueClassName={(latest.annualReturn ?? 0) >= 0 ? "text-up" : "text-down"} />
                  <Stat big label="MDD" value={fmtMdd(latest.mdd)} valueClassName={(latest.mdd ?? 0) > 0.05 ? "text-down" : undefined} />
                  <Stat big label="샤프" value="—" valueClassName="text-tm-muted" />
                  <Stat big label="거래 수" value={fmtNum(latest.tradeCount)} />
                </AutoGrid>
                {curve.length > 1 && <LineChart series={[{ values: curve, color: "#bd93f9", fill: true }]} width={360} height={150} label="빠른 백테스트 수익 곡선" xLabels={[[0, latest.startDate.slice(0, 7)], [0.85, latest.endDate.slice(0, 7)]]} />}
              </>
            )}
            <div className="flex gap-2">
              {editId ? (
                <>
                  <BtnLink href={`/quant-lab/${editId}`} kind="ghost" className="flex-1">상세 백테스트</BtnLink>
                  <BtnLink href={`/quant-lab/${editId}#forward-test`} className="flex-1">포워드 시작</BtnLink>
                </>
              ) : (
                <>
                  <Btn kind="ghost" disabled className="flex-1">상세 백테스트</Btn>
                  <Btn disabled className="flex-1">포워드 시작</Btn>
                </>
              )}
            </div>
          </Panel>
        </PanelCol>
      </PanelRow>
    </TerminalPage>
  );
}
