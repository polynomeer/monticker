"use client";

import { useState, useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { X } from "@phosphor-icons/react";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import { Card } from "@/components/ui/Card";

// ── 조건 블록 타입 ─────────────────────────────────────────────────────────────

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
  GT: "초과", LT: "미만", GTE: "이상", LTE: "이하",
  BETWEEN: "사이", GOLDEN: "골든크로스", DEAD: "데드크로스",
  ABOVE_UPPER: "상단 돌파", BELOW_LOWER: "하단 이탈",
};

interface Condition {
  id: string;
  indicator: string;
  comparator: string;
  params: Record<string, number>;
  value?: number | [number, number];
}

const DEFAULT_ENTRY: Condition[] = [
  { id: "e1", indicator: "CLOSE_VS_MA", comparator: "GT", params: { period: 20 } },
  { id: "e2", indicator: "VOLUME_RATIO", comparator: "GT", params: { period: 20 }, value: 2 },
  { id: "e3", indicator: "RSI", comparator: "BETWEEN", params: { period: 14 }, value: [30, 70] },
];
const DEFAULT_EXIT: Condition[] = [
  { id: "x1", indicator: "PROFIT_RATE", comparator: "GTE", params: {}, value: 8 },
  { id: "x2", indicator: "LOSS_RATE",   comparator: "LTE", params: {}, value: -4 },
];

function uid() { return Math.random().toString(36).slice(2, 8); }

// ── 조건 행 컴포넌트 ──────────────────────────────────────────────────────────

function ConditionRow({ cond, onChange, onRemove }: {
  cond: Condition;
  onChange: (c: Condition) => void;
  onRemove: () => void;
}) {
  const meta = INDICATORS.find(i => i.value === cond.indicator);

  return (
    <div className="flex flex-wrap gap-2 items-center p-3 rounded-lg bg-gray-50 dark:bg-dracula-bg border border-gray-200 dark:border-dracula-line">
      {/* 지표 선택 */}
      <select
        value={cond.indicator}
        onChange={e => {
          const m = INDICATORS.find(i => i.value === e.target.value)!;
          onChange({ ...cond, indicator: e.target.value, comparator: m.comparators[0], params: {}, value: undefined });
        }}
        className="rounded-md bg-white dark:bg-dracula-line text-gray-900 dark:text-dracula-fg text-xs px-2 py-1.5 border border-gray-300 dark:border-none focus:outline-none focus:ring-1 focus:ring-dracula-purple"
      >
        {INDICATORS.map(i => <option key={i.value} value={i.value}>{i.label}</option>)}
      </select>

      {/* 파라미터 (period 등) */}
      {(meta?.params as readonly string[] | undefined)?.includes("period") && (
        <input
          type="number"
          value={cond.params.period ?? 20}
          onChange={e => onChange({ ...cond, params: { ...cond.params, period: +e.target.value } })}
          className="w-16 rounded-md bg-white dark:bg-dracula-line text-gray-900 dark:text-dracula-fg text-xs px-2 py-1.5 border border-gray-300 dark:border-none focus:outline-none focus:ring-1 focus:ring-dracula-purple"
          min={1}
        />
      )}

      {/* 비교 연산자 */}
      <select
        value={cond.comparator}
        onChange={e => onChange({ ...cond, comparator: e.target.value })}
        className="rounded-md bg-white dark:bg-dracula-line text-gray-900 dark:text-dracula-fg text-xs px-2 py-1.5 border border-gray-300 dark:border-none focus:outline-none focus:ring-1 focus:ring-dracula-purple"
      >
        {meta?.comparators.map(c => (
          <option key={c} value={c}>{COMPARATOR_LABEL[c] ?? c}</option>
        ))}
      </select>

      {/* 값 입력 */}
      {(meta as any)?.hasValue && cond.comparator === "BETWEEN" ? (
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
      ) : (meta as any)?.hasValue ? (
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
        className="ml-auto inline-flex items-center justify-center w-6 h-6 text-dracula-red text-xs hover:opacity-70 active:scale-90 transition-transform"
      ><X size={14} weight="bold" aria-hidden /></button>
    </div>
  );
}

// ── 메인 페이지 ───────────────────────────────────────────────────────────────

export default function BuilderPage() {
  const router = useRouter();
  const params = useSearchParams();
  const editId = params.get("edit");
  const qc = useQueryClient();
  const { toast } = useToast();

  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [entryOp, setEntryOp] = useState<"AND" | "OR">("AND");
  const [exitOp,  setExitOp]  = useState<"AND" | "OR">("OR");
  const [entry, setEntry] = useState<Condition[]>(DEFAULT_ENTRY);
  const [exit,  setExit]  = useState<Condition[]>(DEFAULT_EXIT);
  const [positionPct, setPositionPct] = useState(10);

  // 수정 모드: 기존 룰셋 로드
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
      const def = JSON.parse(existing.ruleDefinition);
      setEntryOp(def.entryRules?.operator ?? "AND");
      setExitOp(def.exitRules?.operator ?? "OR");
      setEntry(def.entryRules?.conditions?.map((c: any, i: number) => ({ ...c, id: `e${i}` })) ?? []);
      setExit(def.exitRules?.conditions?.map((c: any, i: number) => ({ ...c, id: `x${i}` })) ?? []);
      setPositionPct(def.positionSizing?.value ?? 10);
    } catch {}
  }, [existing]);

  const buildPayload = () => ({
    name,
    description: description || null,
    ruleDefinition: {
      entryRules: {
        operator: entryOp,
        conditions: entry.map(({ id: _, ...c }) => c),
      },
      exitRules: {
        operator: exitOp,
        conditions: exit.map(({ id: _, ...c }) => c),
      },
      positionSizing: { type: "FIXED_RATIO", value: positionPct },
    },
  });

  const saveMutation = useMutation({
    mutationFn: async () => {
      const payload = buildPayload();
      const url = editId ? `/api/quant/rulesets/${editId}` : "/api/quant/rulesets";
      const method = editId ? "PUT" : "POST";
      const res = await authFetch(url, {
        method,
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

  const addEntry = () => setEntry(p => [...p, { id: uid(), indicator: "CLOSE_VS_MA", comparator: "GT", params: { period: 20 } }]);
  const addExit  = () => setExit(p => [...p, { id: uid(), indicator: "PROFIT_RATE", comparator: "GTE", params: {}, value: 8 }]);

  return (
    <div className="max-w-3xl mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <div className="flex items-center gap-3 mb-8">
        <button onClick={() => router.back()} className="text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg text-sm transition-colors">← 뒤로</button>
        <h1 className="text-xl font-bold tracking-tight text-gray-900 dark:text-dracula-fg">
          {editId ? "룰셋 수정" : "새 룰셋 만들기"}
        </h1>
      </div>

      {/* 기본 정보 */}
      <Card className="p-5" outerClassName="mb-6">
        <h2 className="text-sm font-semibold text-gray-900 dark:text-dracula-fg mb-4">기본 정보</h2>
        <div className="space-y-3">
          <input
            type="text"
            placeholder="전략 이름 (예: 거래량 돌파 단기 전략)"
            value={name}
            onChange={e => setName(e.target.value)}
            className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg placeholder-gray-400 dark:placeholder-dracula-comment px-4 py-2.5 text-sm transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
          />
          <textarea
            placeholder="전략 설명 (선택)"
            value={description}
            onChange={e => setDescription(e.target.value)}
            rows={2}
            className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg placeholder-gray-400 dark:placeholder-dracula-comment px-4 py-2.5 text-sm resize-none transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
          />
        </div>
      </Card>

      {/* 매수 조건 */}
      <Card className="p-5 border-dracula-green/30 dark:border-dracula-green/30" outerClassName="mb-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-sm font-semibold text-dracula-green">매수 조건</h2>
          <div className="flex items-center gap-2">
            <span className="text-xs text-gray-500 dark:text-dracula-comment">조건 연산자:</span>
            {(["AND","OR"] as const).map(op => (
              <button
                key={op}
                onClick={() => setEntryOp(op)}
                className={`px-2 py-0.5 rounded text-xs font-medium transition-all duration-150 active:scale-95 ${entryOp === op ? "bg-dracula-green text-dracula-bg" : "bg-gray-100 dark:bg-dracula-line text-gray-500 dark:text-dracula-comment"}`}
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
          + 조건 추가
        </button>
      </Card>

      {/* 매도 조건 */}
      <Card className="p-5 border-dracula-red/30 dark:border-dracula-red/30" outerClassName="mb-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-sm font-semibold text-dracula-red">매도 조건</h2>
          <div className="flex items-center gap-2">
            <span className="text-xs text-gray-500 dark:text-dracula-comment">조건 연산자:</span>
            {(["AND","OR"] as const).map(op => (
              <button
                key={op}
                onClick={() => setExitOp(op)}
                className={`px-2 py-0.5 rounded text-xs font-medium transition-all duration-150 active:scale-95 ${exitOp === op ? "bg-dracula-red text-white" : "bg-gray-100 dark:bg-dracula-line text-gray-500 dark:text-dracula-comment"}`}
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
          + 조건 추가
        </button>
      </Card>

      {/* 포지션 사이징 */}
      <Card className="p-5" outerClassName="mb-8">
        <h2 className="text-sm font-semibold text-gray-900 dark:text-dracula-fg mb-4">포지션 사이징</h2>
        <div className="flex items-center gap-3">
          <span className="text-sm text-gray-500 dark:text-dracula-comment">1회 매수에 총 자본의</span>
          <input
            type="number"
            value={positionPct}
            onChange={e => setPositionPct(+e.target.value)}
            min={1} max={100}
            className="w-20 rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg px-3 py-1.5 text-sm text-center transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
          />
          <span className="text-sm text-gray-500 dark:text-dracula-comment">% 사용</span>
        </div>
      </Card>

      {/* 저장 버튼 */}
      <button
        onClick={() => saveMutation.mutate()}
        disabled={!name.trim() || entry.length === 0 || exit.length === 0 || saveMutation.isPending}
        className="w-full py-3 rounded-xl bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100"
      >
        {saveMutation.isPending ? "저장 중..." : "룰셋 저장 →"}
      </button>
    </div>
  );
}
