"use client";

import { useState, useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { X, FloppyDisk, Eye } from "@phosphor-icons/react";
import type { RuleCondition, RuleOperator } from "@monticker/types";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";

// ── 상수 ───────────────────────────────────────────────────────────────────────

const INDICATORS = [
  { value: "CLOSE_VS_MA",    label: "현재가 vs 이동평균",   params: ["period"], comparators: ["GT","LT"] },
  { value: "VOLUME_RATIO",   label: "거래량 배율",           params: ["period"], comparators: ["GT","LT"], hasValue: true },
  { value: "RSI",            label: "RSI",                   params: ["period"], comparators: ["GT","LT","BETWEEN"], hasValue: true },
  { value: "MACD_CROSS",     label: "MACD 크로스",           params: [],         comparators: ["GOLDEN","DEAD"] },
  { value: "PRICE_CHANGE",   label: "N일 가격변화율(%)",     params: ["period"], comparators: ["GT","LT"], hasValue: true },
  { value: "BOLLINGER_BAND", label: "볼린저밴드",            params: ["period"], comparators: ["ABOVE_UPPER","BELOW_LOWER"] },
  { value: "PROFIT_RATE",    label: "수익률(%)",             params: [],         comparators: ["GTE","LTE"], hasValue: true, exitOnly: true },
  { value: "LOSS_RATE",      label: "손실률(%)",             params: [],         comparators: ["LTE"],       hasValue: true, exitOnly: true },
] as const;

const COMPARATOR_LABEL: Record<string, string> = {
  GT: ">", LT: "<", GTE: "≥", LTE: "≤",
  BETWEEN: "사이", GOLDEN: "골든크로스", DEAD: "데드크로스",
  ABOVE_UPPER: "상단 돌파", BELOW_LOWER: "하단 이탈",
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

interface ParsedRuleDefinition {
  entryRules?: { operator?: RuleOperator; conditions?: RuleCondition[] };
  exitRules?: { operator?: RuleOperator; conditions?: RuleCondition[] };
  positionSizing?: { value?: number };
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

// ── CSS 공통 ──────────────────────────────────────────────────────────────────

const inputCls = "w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg px-3 py-2 text-xs transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50";
const selectCls = "rounded-md bg-white dark:bg-dracula-line text-gray-900 dark:text-dracula-fg text-xs px-2 py-1.5 border border-gray-300 dark:border-none focus:outline-none focus:ring-1 focus:ring-dracula-purple";

// ── 조건 행 컴포넌트 ──────────────────────────────────────────────────────────

function ConditionRow({ cond, onChange, onRemove }: {
  cond: Condition;
  onChange: (c: Condition) => void;
  onRemove: () => void;
}) {
  const meta = INDICATORS.find(i => i.value === cond.indicator);

  return (
    <div className="flex flex-wrap gap-2 items-center p-3 rounded-lg bg-gray-50 dark:bg-dracula-bg border border-gray-200 dark:border-dracula-line">
      <select
        value={cond.indicator}
        onChange={e => {
          const m = INDICATORS.find(i => i.value === e.target.value)!;
          onChange({ ...cond, indicator: e.target.value, comparator: m.comparators[0], params: {}, value: undefined });
        }}
        className={selectCls}
      >
        {INDICATORS.map(i => <option key={i.value} value={i.value}>{i.label}</option>)}
      </select>

      {(meta?.params as readonly string[] | undefined)?.includes("period") && (
        <input
          type="number"
          value={cond.params.period ?? 20}
          onChange={e => onChange({ ...cond, params: { ...cond.params, period: +e.target.value } })}
          className="w-16 rounded-md bg-white dark:bg-dracula-line text-gray-900 dark:text-dracula-fg text-xs px-2 py-1.5 border border-gray-300 dark:border-none focus:outline-none focus:ring-1 focus:ring-dracula-purple"
          min={1}
        />
      )}

      <select
        value={cond.comparator}
        onChange={e => onChange({ ...cond, comparator: e.target.value })}
        className={selectCls}
      >
        {meta?.comparators.map(c => (
          <option key={c} value={c}>{COMPARATOR_LABEL[c] ?? c}</option>
        ))}
      </select>

      {indicatorHasValue(meta) && cond.comparator === "BETWEEN" ? (
        <>
          <input
            type="number"
            value={Array.isArray(cond.value) ? cond.value[0] : 30}
            onChange={e => onChange({ ...cond, value: [+e.target.value, Array.isArray(cond.value) ? cond.value[1] : 70] })}
            className="w-16 rounded-md bg-white dark:bg-dracula-line text-gray-900 dark:text-dracula-fg text-xs px-2 py-1.5 border border-gray-300 dark:border-none focus:outline-none"
          />
          <span className="text-gray-500 dark:text-dracula-comment text-xs">~</span>
          <input
            type="number"
            value={Array.isArray(cond.value) ? cond.value[1] : 70}
            onChange={e => onChange({ ...cond, value: [Array.isArray(cond.value) ? cond.value[0] : 30, +e.target.value] })}
            className="w-16 rounded-md bg-white dark:bg-dracula-line text-gray-900 dark:text-dracula-fg text-xs px-2 py-1.5 border border-gray-300 dark:border-none focus:outline-none"
          />
        </>
      ) : indicatorHasValue(meta) ? (
        <input
          type="number"
          value={typeof cond.value === "number" ? cond.value : ""}
          onChange={e => onChange({ ...cond, value: +e.target.value })}
          className="w-20 rounded-md bg-white dark:bg-dracula-line text-gray-900 dark:text-dracula-fg text-xs px-2 py-1.5 border border-gray-300 dark:border-none focus:outline-none focus:ring-1 focus:ring-dracula-purple"
        />
      ) : null}

      <button
        onClick={onRemove}
        aria-label="조건 삭제"
        className="ml-auto inline-flex items-center justify-center w-6 h-6 text-dracula-red hover:opacity-70 active:scale-90 transition-transform"
      ><X size={14} weight="bold" aria-hidden /></button>
    </div>
  );
}

// ── 섹션 헤더 ─────────────────────────────────────────────────────────────────

function SectionHeader({ label }: { label: string }) {
  return (
    <h2 className="text-[11px] font-bold uppercase tracking-wider text-gray-400 dark:text-dracula-comment mb-3">
      {label}
    </h2>
  );
}

// ── 메인 페이지 ───────────────────────────────────────────────────────────────

export default function BuilderPage() {
  const router = useRouter();
  const params = useSearchParams();
  const editId = params.get("edit");
  const qc = useQueryClient();
  const { toast } = useToast();

  // 모바일에서 에디터/미리보기 전환
  const [mobileTab, setMobileTab] = useState<"edit" | "preview">("edit");

  const [name,                   setName]                   = useState("");
  const [description,            setDescription]            = useState("");
  const [entryOp,                setEntryOp]                = useState<"AND" | "OR">("AND");
  const [exitOp,                 setExitOp]                 = useState<"AND" | "OR">("OR");
  const [entry,                  setEntry]                  = useState<Condition[]>(DEFAULT_ENTRY);
  const [exit,                   setExit]                   = useState<Condition[]>(DEFAULT_EXIT);
  const [positionPct,            setPositionPct]            = useState(10);
  const [universeMarket,         setUniverseMarket]         = useState("all");
  const [universeMarketCapTier,  setUniverseMarketCapTier]  = useState("all");

  const { data: existing } = useQuery({
    queryKey: ["quant", "ruleset", editId],
    queryFn: async () => {
      const res = await authFetch(`/api/quant/rulesets/${editId}`);
      if (!res.ok) throw new Error("룰셋 조회 실패");
      return res.json();
    },
    enabled: !!editId,
  });

  useEffect(() => {
    if (!existing) return;
    setName(existing.name);
    setDescription(existing.description ?? "");
    try {
      const def: ParsedRuleDefinition = JSON.parse(existing.ruleDefinition);
      setEntryOp(def.entryRules?.operator ?? "AND");
      setExitOp(def.exitRules?.operator ?? "OR");
      setEntry(def.entryRules?.conditions?.map((c, i) => ({ ...c, id: `e${i}`, params: (c as Condition).params ?? {} })) ?? []);
      setExit(def.exitRules?.conditions?.map((c, i) => ({ ...c, id: `x${i}`, params: (c as Condition).params ?? {} })) ?? []);
      setPositionPct(def.positionSizing?.value ?? 10);
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
      entryRules:    { operator: entryOp, conditions: entry.map(({ id, ...c }) => c) },
      exitRules:     { operator: exitOp,  conditions: exit.map(({ id, ...c }) => c) },
      positionSizing: { type: "FIXED_RATIO", value: positionPct },
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

  const canSave = name.trim().length > 0 && entry.length > 0 && exit.length > 0;

  const addEntry = () => setEntry(p => [...p, { id: uid(), indicator: "CLOSE_VS_MA",  comparator: "GT",  params: { period: 20 } }]);
  const addExit  = () => setExit(p => [...p,  { id: uid(), indicator: "PROFIT_RATE",  comparator: "GTE", params: {},            value: 8 }]);

  // ── 미리보기 패널 ────────────────────────────────────────────────────────────

  const universeLabel = [
    UNIVERSE_MARKETS.find(m => m.key === universeMarket)?.label ?? "전체",
    universeMarket !== "overseas"
      ? UNIVERSE_MARKET_CAP_TIERS.find(t => t.key === universeMarketCapTier)?.label
      : undefined,
  ].filter(Boolean).join(" · ");

  const PreviewPanel = () => (
    <div className="flex flex-col h-full">
      <div className="flex-none px-4 py-3 border-b border-gray-100 dark:border-white/5 flex items-center gap-2">
        <Eye size={14} weight="bold" className="text-dracula-purple" aria-hidden />
        <span className="text-[11px] font-bold uppercase tracking-wider text-dracula-purple">전략 미리보기</span>
      </div>

      <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4 [scrollbar-width:thin]">
        {/* 전략 이름 */}
        <div>
          <p className="text-[10px] uppercase tracking-wider text-gray-400 dark:text-dracula-comment mb-1">전략명</p>
          <p className="text-sm font-bold text-gray-900 dark:text-dracula-fg">
            {name.trim() || <span className="text-gray-300 dark:text-dracula-line italic">미입력</span>}
          </p>
          {description && (
            <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5 line-clamp-2">{description}</p>
          )}
        </div>

        {/* 유니버스 */}
        <div>
          <p className="text-[10px] uppercase tracking-wider text-gray-400 dark:text-dracula-comment mb-1">유니버스</p>
          <span className="inline-block text-xs font-medium px-2 py-0.5 rounded bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg">
            {universeLabel}
          </span>
        </div>

        {/* 매수 조건 */}
        <div>
          <div className="flex items-center gap-2 mb-2">
            <span className="text-[10px] uppercase tracking-wider text-dracula-green font-bold">매수 조건</span>
            <span className="text-[9px] font-bold px-1.5 py-0.5 rounded bg-dracula-green/15 text-dracula-green">{entryOp}</span>
          </div>
          {entry.length === 0 ? (
            <p className="text-[11px] text-gray-400 dark:text-dracula-comment italic">조건 없음</p>
          ) : (
            <ol className="space-y-1.5">
              {entry.map((c, i) => (
                <li key={c.id} className="flex items-start gap-2 text-[11px]">
                  <span className="shrink-0 w-4 text-right text-gray-300 dark:text-dracula-line tabular-nums">{i + 1}.</span>
                  <span className="font-mono text-gray-800 dark:text-dracula-fg leading-snug">{condToText(c)}</span>
                </li>
              ))}
            </ol>
          )}
        </div>

        {/* 매도 조건 */}
        <div>
          <div className="flex items-center gap-2 mb-2">
            <span className="text-[10px] uppercase tracking-wider text-dracula-red font-bold">매도 조건</span>
            <span className="text-[9px] font-bold px-1.5 py-0.5 rounded bg-dracula-red/15 text-dracula-red">{exitOp}</span>
          </div>
          {exit.length === 0 ? (
            <p className="text-[11px] text-gray-400 dark:text-dracula-comment italic">조건 없음</p>
          ) : (
            <ol className="space-y-1.5">
              {exit.map((c, i) => (
                <li key={c.id} className="flex items-start gap-2 text-[11px]">
                  <span className="shrink-0 w-4 text-right text-gray-300 dark:text-dracula-line tabular-nums">{i + 1}.</span>
                  <span className="font-mono text-gray-800 dark:text-dracula-fg leading-snug">{condToText(c)}</span>
                </li>
              ))}
            </ol>
          )}
        </div>

        {/* 포지션 사이징 */}
        <div>
          <p className="text-[10px] uppercase tracking-wider text-gray-400 dark:text-dracula-comment mb-1">포지션 사이징</p>
          <p className="text-xs font-mono text-gray-800 dark:text-dracula-fg">
            1회 매수 = 총 자본의 <strong>{positionPct}%</strong>
          </p>
        </div>

        {/* 경고 */}
        {!canSave && (
          <div className="p-2.5 rounded-lg bg-dracula-orange/10 border border-dracula-orange/30 text-[11px] text-dracula-orange">
            {!name.trim() && "· 전략 이름을 입력하세요\n"}
            {entry.length === 0 && "· 매수 조건을 1개 이상 추가하세요\n"}
            {exit.length === 0 && "· 매도 조건을 1개 이상 추가하세요"}
          </div>
        )}
      </div>

      {/* 저장 버튼 */}
      <div className="flex-none px-4 py-4 border-t border-gray-100 dark:border-white/5">
        <button
          onClick={() => saveMutation.mutate()}
          disabled={!canSave || saveMutation.isPending}
          className="w-full py-2.5 rounded-xl bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100 inline-flex items-center justify-center gap-1.5"
        >
          <FloppyDisk size={14} weight="bold" aria-hidden />
          {saveMutation.isPending ? "저장 중..." : editId ? "룰셋 업데이트" : "룰셋 저장"}
        </button>
        <p className="mt-2 text-[10px] text-gray-400 dark:text-dracula-comment text-center">
          저장 후 바로 백테스트 화면으로 이동합니다
        </p>
      </div>
    </div>
  );

  return (
    <div className="flex flex-col animate-fade-up">
      {/* ── 헤더 바 ────────────────────────────────────────────────────────── */}
      <div className="flex items-center gap-3 px-4 py-3 border-b border-gray-100 dark:border-white/5
                      bg-white dark:bg-dracula-bg">
        <button
          onClick={() => router.back()}
          className="text-gray-400 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg text-xs transition-colors shrink-0"
        >
          ← 뒤로
        </button>
        <div className="w-px h-5 bg-gray-200 dark:bg-dracula-line" />
        <h1 className="text-sm font-bold text-gray-900 dark:text-dracula-fg">
          {editId ? "룰셋 수정" : "새 룰셋 만들기"}
        </h1>

        {/* 모바일 탭 전환 */}
        <div className="ml-auto lg:hidden flex items-center gap-0.5 p-0.5 rounded-lg bg-gray-100 dark:bg-dracula-line/30">
          {(["edit", "preview"] as const).map(t => (
            <button
              key={t}
              onClick={() => setMobileTab(t)}
              className={`px-3 py-1 rounded-md text-[11px] font-medium transition-all duration-150 ${
                mobileTab === t
                  ? "bg-white dark:bg-dracula-bg text-gray-900 dark:text-dracula-fg shadow-sm"
                  : "text-gray-500 dark:text-dracula-comment"
              }`}
            >
              {t === "edit" ? "에디터" : "미리보기"}
            </button>
          ))}
        </div>
      </div>

      {/* ── 분할 레이아웃: [에디터 | 미리보기] ─────────────────────────────── */}
      <div className="flex flex-col lg:flex-row lg:divide-x dark:lg:divide-white/5
                      lg:min-h-[calc(100vh-108px)]">

        {/* ===== 좌측: 에디터 ===== */}
        <div className={`flex-1 min-w-0 overflow-y-auto px-4 py-5 space-y-5
                         ${mobileTab === "preview" ? "hidden lg:block" : ""}`}>

          {/* 기본 정보 */}
          <section>
            <SectionHeader label="기본 정보" />
            <div className="space-y-3">
              <input
                type="text"
                aria-label="전략 이름"
                placeholder="전략 이름 (예: 거래량 돌파 단기 전략)"
                value={name}
                onChange={e => setName(e.target.value)}
                className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg placeholder-gray-400 dark:placeholder-dracula-comment px-4 py-2.5 text-sm transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
              />
              <textarea
                aria-label="전략 설명"
                placeholder="전략 설명 (선택)"
                value={description}
                onChange={e => setDescription(e.target.value)}
                rows={2}
                className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg placeholder-gray-400 dark:placeholder-dracula-comment px-4 py-2.5 text-sm resize-none transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
              />
            </div>
          </section>

          <div className="border-t dark:border-dracula-line/60" />

          {/* 유니버스 */}
          <section>
            <SectionHeader label="유니버스 설정" />
            <p className="text-[11px] text-gray-500 dark:text-dracula-comment mb-3">
              이 전략이 대상으로 하는 종목군입니다.
            </p>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">시장</label>
                <select
                  value={universeMarket}
                  onChange={e => {
                    setUniverseMarket(e.target.value);
                    if (e.target.value === "overseas") setUniverseMarketCapTier("all");
                  }}
                  className={inputCls}
                >
                  {UNIVERSE_MARKETS.map(m => <option key={m.key} value={m.key}>{m.label}</option>)}
                </select>
              </div>
              {universeMarket !== "overseas" && (
                <div>
                  <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">시가총액</label>
                  <select
                    value={universeMarketCapTier}
                    onChange={e => setUniverseMarketCapTier(e.target.value)}
                    className={inputCls}
                  >
                    {UNIVERSE_MARKET_CAP_TIERS.map(t => <option key={t.key} value={t.key}>{t.label}</option>)}
                  </select>
                </div>
              )}
            </div>
          </section>

          <div className="border-t dark:border-dracula-line/60" />

          {/* 매수 조건 */}
          <section>
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-[11px] font-bold uppercase tracking-wider text-dracula-green">
                매수 조건
              </h2>
              <div className="flex items-center gap-1.5">
                <span className="text-[10px] text-gray-400 dark:text-dracula-comment">조건 연산자</span>
                {(["AND","OR"] as const).map(op => (
                  <button
                    key={op}
                    onClick={() => setEntryOp(op)}
                    className={`px-2 py-0.5 rounded text-[11px] font-bold transition-all duration-150 active:scale-95 ${
                      entryOp === op
                        ? "bg-dracula-green text-dracula-bg"
                        : "bg-gray-100 dark:bg-dracula-line text-gray-500 dark:text-dracula-comment hover:opacity-80"
                    }`}
                  >
                    {op}
                  </button>
                ))}
              </div>
            </div>
            <div className="space-y-2">
              {entry.map(c => (
                <ConditionRow
                  key={c.id}
                  cond={c}
                  onChange={nc => setEntry(p => p.map(x => x.id === nc.id ? nc : x))}
                  onRemove={() => setEntry(p => p.filter(x => x.id !== c.id))}
                />
              ))}
            </div>
            <button onClick={addEntry} className="mt-3 text-xs text-dracula-green hover:opacity-70 transition-opacity">
              + 매수 조건 추가
            </button>
          </section>

          <div className="border-t dark:border-dracula-line/60" />

          {/* 매도 조건 */}
          <section>
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-[11px] font-bold uppercase tracking-wider text-dracula-red">
                매도 조건
              </h2>
              <div className="flex items-center gap-1.5">
                <span className="text-[10px] text-gray-400 dark:text-dracula-comment">조건 연산자</span>
                {(["AND","OR"] as const).map(op => (
                  <button
                    key={op}
                    onClick={() => setExitOp(op)}
                    className={`px-2 py-0.5 rounded text-[11px] font-bold transition-all duration-150 active:scale-95 ${
                      exitOp === op
                        ? "bg-dracula-red text-white"
                        : "bg-gray-100 dark:bg-dracula-line text-gray-500 dark:text-dracula-comment hover:opacity-80"
                    }`}
                  >
                    {op}
                  </button>
                ))}
              </div>
            </div>
            <div className="space-y-2">
              {exit.map(c => (
                <ConditionRow
                  key={c.id}
                  cond={c}
                  onChange={nc => setExit(p => p.map(x => x.id === nc.id ? nc : x))}
                  onRemove={() => setExit(p => p.filter(x => x.id !== c.id))}
                />
              ))}
            </div>
            <button onClick={addExit} className="mt-3 text-xs text-dracula-red hover:opacity-70 transition-opacity">
              + 매도 조건 추가
            </button>
          </section>

          <div className="border-t dark:border-dracula-line/60" />

          {/* 포지션 사이징 */}
          <section>
            <SectionHeader label="포지션 사이징" />
            <div className="flex items-center gap-3">
              <span className="text-sm text-gray-500 dark:text-dracula-comment">1회 매수에 총 자본의</span>
              <input
                type="number"
                value={positionPct}
                onChange={e => setPositionPct(+e.target.value)}
                min={1} max={100}
                className="w-20 rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg px-3 py-1.5 text-sm text-center focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
              />
              <span className="text-sm text-gray-500 dark:text-dracula-comment">% 사용</span>
            </div>
          </section>

          {/* 모바일 저장 버튼 (lg 미만에서만) */}
          <div className="lg:hidden pb-4">
            <button
              onClick={() => saveMutation.mutate()}
              disabled={!canSave || saveMutation.isPending}
              className="w-full py-3 rounded-xl bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100"
            >
              {saveMutation.isPending ? "저장 중..." : editId ? "룰셋 업데이트 →" : "룰셋 저장 →"}
            </button>
          </div>
        </div>

        {/* ===== 우측: 라이브 미리보기 (데스크톱 상시 | 모바일 탭 전환) ===== */}
        <div className={`lg:w-[300px] lg:flex-none dark:bg-[#1e202a]
                         ${mobileTab === "edit" ? "hidden lg:flex lg:flex-col" : "flex flex-col min-h-[60vh]"}`}>
          <PreviewPanel />
        </div>
      </div>
    </div>
  );
}
