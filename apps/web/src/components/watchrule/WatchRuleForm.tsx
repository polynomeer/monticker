"use client";

import { useEffect, useState, type KeyboardEvent, type ReactNode } from "react";
import type {
  UpdateWatchRuleRequest,
  WatchRuleDetectedEventType,
  WatchRuleEventType,
  WatchRuleOrderType,
  WatchRuleResponse,
  WatchRuleSide,
  WatchRuleSizeType,
} from "@monticker/types";
import { Btn, BuySell, Checkbox, Chip, Field, Icon, Notice, SelectBox, TextField } from "@/components/terminal";
import { authFetch } from "@/services/api";
import { buildWatchRulePatch } from "./watchRulePatch";

export interface StockHit { id: number; symbol: string; name: string; }

const EVENT_OPTIONS: { value: WatchRuleDetectedEventType; label: string; hint: string }[] = [
  { value: "VOLUME_SURGE", label: "거래량 급증", hint: "평소보다 거래가 크게 몰릴 때" },
  { value: "PRICE_SPIKE", label: "가격 급등", hint: "짧은 시간에 크게 오를 때" },
  { value: "PRICE_DROP", label: "가격 급락", hint: "짧은 시간에 크게 내릴 때" },
];

const COOLDOWN_OPTIONS = [
  { value: 0, label: "없음" },
  { value: 300, label: "5분" },
  { value: 600, label: "10분" },
  { value: 3600, label: "1시간" },
  { value: 86400, label: "하루" },
];

const WINDOW_OPTIONS = [
  { value: 300, label: "5분" },
  { value: 1800, label: "30분" },
  { value: 3600, label: "1시간" },
  { value: 86400, label: "하루" },
];

// 백엔드 검증(quantity > 0)과 별개로 화면에서도 상한을 둔다 — 실수로 1e9 를 넣는 걸 막는다.
export const MAX_QUANTITY = 1_000_000;

// ADR-095 — 서버 범위와 같다(WatchRuleSizing): 지정가 오프셋 ±1000bp(±10%), 계좌 비율 1~25%.
export const MAX_OFFSET_BPS = 1000;
export const MIN_EQUITY_PCT = 1;
export const MAX_EQUITY_PCT = 25;

interface StrategyOption { id: string; name: string; origin: "내 전략" | "구독" }

interface GroupOption { id: number; name: string; count: number }

/** 내 관심종목 그룹 — 그룹 대상 규칙에 고를 수 있다(서버가 소유를 다시 확인한다, 남의 그룹은 404). */
async function loadGroups(): Promise<GroupOption[]> {
  const body = await authFetch("/api/watchlists").then((r) => (r.ok ? r.json() : [])).catch(() => []);
  return (Array.isArray(body) ? body : []).map((g: { id: number; name: string; items?: unknown[] }) => ({
    id: g.id, name: g.name, count: g.items?.length ?? 0,
  }));
}

/** bp → "−1.00%" 같은 표시 */
export function offsetLabel(bps: number): string {
  const pct = (bps / 100).toFixed(2);
  return bps > 0 ? `+${pct}%` : bps < 0 ? `−${pct.slice(1)}%` : "0.00%";
}

/** 전략 신호 규칙에 고를 수 있는 전략 — 내 룰셋 + 구독한 마켓 전략(서버가 등록 때 다시 확인한다, ADR-035). */
async function loadStrategies(): Promise<StrategyOption[]> {
  const [own, market] = await Promise.all([
    authFetch("/api/quant/rulesets").then((r) => (r.ok ? r.json() : [])).catch(() => []),
    authFetch("/api/quant/market?size=100").then((r) => (r.ok ? r.json() : [])).catch(() => []),
  ]);
  const mine: StrategyOption[] = (Array.isArray(own) ? own : []).map((r: { id: string; name: string }) => ({ id: r.id, name: r.name, origin: "내 전략" }));
  const subscribed: StrategyOption[] = (Array.isArray(market) ? market : [])
    .filter((m: { isSubscribed?: boolean }) => m.isSubscribed)
    .map((m: { ruleset_id: string; name: string }) => ({ id: m.ruleset_id, name: m.name, origin: "구독" }));
  const seen = new Set(mine.map((s) => s.id));
  return [...mine, ...subscribed.filter((s) => !seen.has(s.id))];
}

export interface WatchRuleFormValue {
  /** 대상이 종목일 때 */
  stockId?: number;
  eventType: WatchRuleEventType;
  side: WatchRuleSide;
  /** 수량 기준이 주 수일 때 */
  quantity?: number;
  minImportanceScore: number;
  cooldownSec: number;
  /** ADR-077 — 아래는 값이 있을 때만 담는다 */
  name?: string;
  ruleSetId?: string;
  signalDirection?: WatchRuleSide;
  requiredEventTypes?: WatchRuleDetectedEventType[];
  conditionWindowSec?: number;
  dailyLimit?: number;
  /** ADR-095 — 기본값(종목·시장가·주 수)이 아닐 때만 담는다 */
  targetType?: "GROUP";
  targetGroupId?: number;
  orderType?: "LIMIT";
  limitOffsetBps?: number;
  sizeType?: "EQUITY_PCT";
  equityPct?: number;
}

/** 폼이 들고 있는 입력(입력칸 문자열 그대로). 생성·수정이 같은 검증 함수를 쓴다. */
export interface WatchRuleFormInput {
  target: "STOCK" | "GROUP";
  stockId: number | null;
  groupId: number | null;
  eventType: WatchRuleEventType;
  side: WatchRuleSide;
  orderType: WatchRuleOrderType;
  offsetBps: string;
  sizeType: WatchRuleSizeType;
  quantity: string;
  equityPct: string;
  minImportance: string;
  cooldownSec: number;
  name: string;
  ruleSetId: string;
  signalDirection: WatchRuleSide;
  required: WatchRuleDetectedEventType[];
  windowSec: number;
  dailyLimit: string;
}

/**
 * 생성·수정 공통 검증 — 서버(WatchRuleService·WatchRuleSizing, V92 CHECK)와 같은 범위다.
 * 빈 입력칸은 0으로 읽지 않고 오류로 본다. `checkStrategy = false`면 전략 선택을 보지 않는다(수정은 감지 조건을 바꾸지 않는다).
 */
export function validateWatchRuleForm(
  f: WatchRuleFormInput,
  opts: { checkStrategy?: boolean } = {},
): { error: string } | { value: WatchRuleFormValue } {
  const isSignal = f.eventType === "QUANT_SIGNAL";
  if (f.target === "STOCK" && f.stockId == null) return { error: "종목을 선택해주세요." };
  if (f.target === "GROUP" && f.groupId == null) return { error: "관심종목 그룹을 골라주세요." };
  const qty = Number(f.quantity);
  if (f.sizeType === "SHARES") {
    if (!f.quantity.trim() || !Number.isInteger(qty) || qty < 1) return { error: "수량은 1 이상의 정수여야 합니다." };
    if (qty > MAX_QUANTITY) return { error: `수량은 ${MAX_QUANTITY.toLocaleString("ko-KR")}주를 넘을 수 없습니다.` };
  }
  const pct = Number(f.equityPct);
  // 소수 자릿수는 문자열로 본다 — pct * 100 비교는 부동소수 오차로 2.55 같은 유효값을 거부했다(서버는 scale ≤ 2면 받는다)
  if (
    f.sizeType === "EQUITY_PCT" &&
    (!/^\d+(\.\d{1,2})?$/.test(f.equityPct.trim()) || pct < MIN_EQUITY_PCT || pct > MAX_EQUITY_PCT)
  ) {
    return { error: `계좌 비율은 ${MIN_EQUITY_PCT}~${MAX_EQUITY_PCT}% 사이(소수점 둘째 자리까지)여야 합니다.` };
  }
  const bps = Number(f.offsetBps);
  if (f.orderType === "LIMIT" && (!f.offsetBps.trim() || !Number.isInteger(bps) || Math.abs(bps) > MAX_OFFSET_BPS)) {
    return { error: `지정가 오프셋은 −${MAX_OFFSET_BPS}~${MAX_OFFSET_BPS}bp(±10%) 사이의 정수여야 합니다.` };
  }
  const importance = isSignal ? 0 : Number(f.minImportance);
  if ((!isSignal && !f.minImportance.trim()) || !Number.isInteger(importance) || importance < 0 || importance > 100) {
    return { error: "중요도 하한은 0~100 사이의 정수여야 합니다." };
  }
  if ((opts.checkStrategy ?? true) && isSignal && !f.ruleSetId) return { error: "신호를 받을 전략을 골라주세요." };
  const limit = f.dailyLimit.trim() ? Number(f.dailyLimit) : null;
  if (limit != null && (!Number.isInteger(limit) || limit < 1 || limit > 1000)) {
    return { error: "하루 최대 발동은 1~1000회 사이의 정수여야 합니다." };
  }
  const trimmed = f.name.trim();
  if (trimmed.length > 100) return { error: "규칙 이름은 100자 이하여야 합니다." };
  const compound = !isSignal ? f.required.filter((t) => t !== f.eventType) : [];
  return {
    value: {
      ...(f.target === "STOCK" ? { stockId: f.stockId! } : { targetType: "GROUP" as const, targetGroupId: f.groupId! }),
      eventType: f.eventType, side: f.side,
      ...(f.sizeType === "SHARES" ? { quantity: qty } : { sizeType: "EQUITY_PCT" as const, equityPct: pct }),
      minImportanceScore: importance, cooldownSec: f.cooldownSec,
      ...(trimmed ? { name: trimmed } : {}),
      ...(isSignal ? { ruleSetId: f.ruleSetId, signalDirection: f.signalDirection } : {}),
      ...(compound.length ? { requiredEventTypes: compound, conditionWindowSec: f.windowSec } : {}),
      ...(limit != null ? { dailyLimit: limit } : {}),
      ...(f.orderType === "LIMIT" ? { orderType: "LIMIT" as const, limitOffsetBps: bps } : {}),
    },
  };
}

interface CreateProps {
  rule?: undefined;
  onSubmit: (value: WatchRuleFormValue) => void;
  submitting: boolean;
}

/** ADR-098 — 수정 모드. 규칙 값으로 채우고, 바뀐 값만 담은 PATCH 본문을 onUpdate로 넘긴다. */
interface EditProps {
  rule: WatchRuleResponse;
  /** 대상 종목의 표시 정보 — 모르면 "종목 #id"로 보인다 */
  initialStock?: StockHit | null;
  onUpdate: (patch: UpdateWatchRuleRequest) => void;
  onCancel: () => void;
  submitting: boolean;
  /** 서버가 돌려준 400·404 메시지 */
  serverError?: string | null;
}

type Props = CreateProps | EditProps;

const EVENT_NAME: Record<WatchRuleEventType, string> = {
  VOLUME_SURGE: "거래량 급증", PRICE_SPIKE: "가격 급등", PRICE_DROP: "가격 급락", QUANT_SIGNAL: "전략 신호",
};

/** 수정 모드에서 바꿀 수 없는 감지 조건 한 줄 */
function triggerSummary(rule: WatchRuleResponse): string {
  if (rule.eventType === "QUANT_SIGNAL") {
    return `퀀트랩 · ${rule.ruleSetName ?? "전략"} ${rule.signalDirection === "SELL" ? "매도" : "매수"} 신호`;
  }
  const req = rule.requiredEventTypes ?? [];
  return `이벤트 · ${EVENT_NAME[rule.eventType]}${req.length ? ` + ${req.map((t) => EVENT_NAME[t]).join(" + ")}` : ""}`;
}

function Step({ n, title, children }: { n: number; title: ReactNode; children: ReactNode }) {
  return (
    <fieldset className="m-0 flex min-w-0 flex-col gap-2 border-0 p-0">
      <legend className="mb-2 p-0 text-2xs text-tm-muted">{n}. {title}</legend>
      {children}
    </fieldset>
  );
}

function initialStockOf(rule: WatchRuleResponse | undefined, known: StockHit | null | undefined): StockHit | null {
  if (!rule || rule.stockId == null) return null;
  return known && known.id === rule.stockId ? known : { id: rule.stockId, symbol: "", name: `종목 #${rule.stockId}` };
}

const str = (v: number | null | undefined) => (v == null ? "" : String(v));

/**
 * 시안 WatchRules "새 규칙" 에디터 — 감지 → 종목 → 주문 → 안전장치 4단계.
 * `rule`을 주면 같은 폼이 수정 모드가 된다(ADR-098): 감지 조건·매수/매도는 PATCH가 받지 않아 읽기 전용으로 보인다.
 */
export function WatchRuleForm(props: Props) {
  const { submitting } = props;
  const rule = props.rule;
  const editing = rule != null;
  const edit = editing ? (props as EditProps) : null;
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<StockHit[]>([]);
  const [stock, setStock] = useState<StockHit | null>(() => initialStockOf(rule, edit?.initialStock));
  const [eventType, setEventType] = useState<WatchRuleEventType>(rule?.eventType ?? "VOLUME_SURGE");
  const [name, setName] = useState(rule?.name ?? "");
  const [strategies, setStrategies] = useState<StrategyOption[] | null>(null);
  const [ruleSetId, setRuleSetId] = useState(rule?.ruleSetId ?? "");
  const [signalDirection, setSignalDirection] = useState<WatchRuleSide>(rule?.signalDirection ?? "BUY");
  const [required, setRequired] = useState<WatchRuleDetectedEventType[]>(rule?.requiredEventTypes ?? []);
  const [windowSec, setWindowSec] = useState(rule?.conditionWindowSec ?? 1800);
  const [dailyLimit, setDailyLimit] = useState(str(rule?.dailyLimit));
  const isSignal = eventType === "QUANT_SIGNAL";
  // ADR-095 — 대상(종목·그룹), 주문 유형, 수량 기준. 수정 모드는 규칙 값으로 채우고, 규칙에 없는 값은 비워 둔다(지어내지 않는다).
  const [target, setTarget] = useState<"STOCK" | "GROUP">(rule?.targetType ?? "STOCK");
  const [groups, setGroups] = useState<GroupOption[] | null>(null);
  const [groupId, setGroupId] = useState<number | null>(rule?.targetGroupId ?? null);
  const [orderType, setOrderType] = useState<WatchRuleOrderType>(rule?.orderType ?? "MARKET");
  const [offsetBps, setOffsetBps] = useState(editing ? str(rule.limitOffsetBps) : "-50");
  const [sizeType, setSizeType] = useState<WatchRuleSizeType>(rule?.sizeType ?? "SHARES");
  const [equityPct, setEquityPct] = useState(editing ? str(rule.equityPct) : "5");
  const [reactivate, setReactivate] = useState(true);

  // 종목 이름이 폼을 연 뒤에 도착하면("종목 #id"로 채웠던 경우) 같은 종목일 때만 표시를 채운다
  const knownStock = edit?.initialStock;
  useEffect(() => {
    if (!knownStock) return;
    setStock((cur) => (cur && cur.id === knownStock.id && !cur.symbol ? knownStock : cur));
  }, [knownStock]);

  // 그룹 목록은 "관심종목 그룹"을 고를 때만 불러온다
  useEffect(() => {
    if (target !== "GROUP" || groups) return;
    let cancelled = false;
    loadGroups().then((g) => { if (!cancelled) { setGroups(g); if (g[0]) setGroupId((cur) => cur ?? g[0].id); } });
    return () => { cancelled = true; };
  }, [target, groups]);

  // 전략 목록은 "전략 신호"를 고를 때만 불러온다(수정 모드는 전략을 바꾸지 않는다)
  useEffect(() => {
    if (!isSignal || strategies || editing) return;
    let cancelled = false;
    loadStrategies().then((s) => { if (!cancelled) { setStrategies(s); if (s[0]) setRuleSetId((cur) => cur || s[0].id); } });
    return () => { cancelled = true; };
  }, [isSignal, strategies, editing]);
  const [side, setSide] = useState<WatchRuleSide>(rule?.side ?? "BUY");
  const [quantity, setQuantity] = useState(editing ? str(rule.quantity) : "10");
  const [minImportance, setMinImportance] = useState(editing ? String(rule.minImportanceScore) : "0");
  const [cooldownSec, setCooldownSec] = useState(rule?.cooldownSec ?? 600);
  const [error, setError] = useState<string | null>(null);

  // 이전 검색어의 늦은 응답이 최신 결과를 덮지 않도록 AbortController 로 취소한다.
  useEffect(() => {
    if (query.length < 1) { setResults([]); return; }
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const r = await fetch(`/api/stocks/search?query=${encodeURIComponent(query)}`, { signal: controller.signal });
        if (r.ok) setResults((await r.json()).slice(0, 6));
      } catch (e) {
        if ((e as Error).name !== "AbortError") throw e;
      }
    }, 200);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [query]);

  const input: WatchRuleFormInput = {
    target, stockId: stock?.id ?? null, groupId, eventType, side, orderType, offsetBps, sizeType, quantity, equityPct,
    minImportance, cooldownSec, name, ruleSetId, signalDirection, required, windowSec, dailyLimit,
  };
  // ADR-098 — 그룹이 지워져 꺼진 규칙은 대상을 바꾸면 다시 켤 수 있다
  const groupMissing = editing && !!rule.targetGroupMissing;
  const targetChanged = editing && (
    target !== (rule.targetType ?? "STOCK") ||
    (target === "STOCK" ? stock?.id !== rule.stockId : groupId !== rule.targetGroupId)
  );

  const submit = () => {
    setError(null);
    const result = validateWatchRuleForm(input, { checkStrategy: !editing });
    if ("error" in result) { setError(result.error); return; }
    if (!edit) { (props as CreateProps).onSubmit(result.value); return; }
    const patch = buildWatchRulePatch(edit.rule, result.value, { reactivate });
    if (Object.keys(patch).length === 0) { setError("바뀐 내용이 없습니다."); return; }
    edit.onUpdate(patch);
  };

  // Esc로 수정 취소 — 종목 검색 결과가 열려 있으면 그것부터 닫는다
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== "Escape" || !edit) return;
    e.preventDefault();
    if (results.length) { setResults([]); return; }
    edit.onCancel();
  };

  // 규칙의 쿨다운이 목록에 없는 값(API로 만든 규칙)이면 그 값을 그대로 고를 수 있게 둔다
  const cooldownOptions = !editing || COOLDOWN_OPTIONS.some((o) => o.value === rule.cooldownSec)
    ? COOLDOWN_OPTIONS
    : [...COOLDOWN_OPTIONS, { value: rule.cooldownSec, label: `${rule.cooldownSec}초 (현재 값)` }].sort((a, b) => a.value - b.value);

  const reset = () => { setStock(null); setQuery(""); setResults([]); };
  const hint = EVENT_OPTIONS.find((o) => o.value === eventType)?.hint;
  const toggleRequired = (t: WatchRuleDetectedEventType) =>
    setRequired((cur) => (cur.includes(t) ? cur.filter((x) => x !== t) : [...cur, t]));

  const importanceField = (
    <div className="flex gap-2">
      <Field label="중요도 하한" aria-label="중요도 하한" unit="점 이상" type="number" min={0} max={100} value={minImportance} onChange={(e) => setMinImportance(e.target.value)} />
    </div>
  );

  return (
    <div className="flex min-w-0 flex-col gap-3" onKeyDown={onKeyDown}>
      <TextField label="규칙 이름 (선택)" aria-label="규칙 이름" maxLength={100} placeholder="예: 거래량 터지면 10주" value={name} onChange={(e) => setName(e.target.value)} />

      <Step n={1} title="무엇을 감지할까요">
        {editing ? (
          <>
            <p className="m-0 text-13 text-tm-soft">{triggerSummary(rule)}</p>
            {!isSignal && importanceField}
            <span className="text-2xs text-tm-muted">
              감지 조건({isSignal ? "전략·신호 방향" : "이벤트·복합 조건"})은 수정할 수 없습니다 — 바꾸려면 새 규칙을 만드세요.
            </span>
          </>
        ) : isSignal ? (
          <>
            <SelectBox aria-label="감지할 이벤트" value={eventType} onChange={(e) => setEventType(e.target.value as WatchRuleEventType)}>
              {EVENT_OPTIONS.map((o) => <option key={o.value} value={o.value}>이벤트 · {o.label}</option>)}
              <option value="QUANT_SIGNAL">퀀트랩 · 전략 신호</option>
            </SelectBox>
            <SelectBox label="전략" aria-label="전략" value={ruleSetId} onChange={(e) => setRuleSetId(e.target.value)} disabled={!strategies?.length}>
              {strategies == null && <option value="">불러오는 중...</option>}
              {strategies?.length === 0 && <option value="">쓸 수 있는 전략이 없습니다</option>}
              {strategies?.map((s) => <option key={s.id} value={s.id}>{s.name} · {s.origin}</option>)}
            </SelectBox>
            <SelectBox label="신호 방향" aria-label="신호 방향" value={signalDirection} onChange={(e) => setSignalDirection(e.target.value as WatchRuleSide)}>
              <option value="BUY">매수 신호일 때</option>
              <option value="SELL">매도 신호일 때</option>
            </SelectBox>
            <span className="text-2xs text-tm-muted">이 종목에서 포워드 테스트 중인 전략이 신호를 내면 발동합니다. 내 전략이나 구독한 전략만 고를 수 있어요.</span>
          </>
        ) : (
          <>
            <SelectBox aria-label="감지할 이벤트" value={eventType} onChange={(e) => setEventType(e.target.value as WatchRuleEventType)}>
              {EVENT_OPTIONS.map((o) => <option key={o.value} value={o.value}>이벤트 · {o.label}</option>)}
              <option value="QUANT_SIGNAL">퀀트랩 · 전략 신호</option>
            </SelectBox>
            {importanceField}
            <span className="text-2xs text-tm-muted">{hint} · 이벤트 강도가 하한 미만이면 발동하지 않습니다 (0이면 모든 이벤트)</span>
            <div className="flex flex-col gap-1.5" role="group" aria-label="복합 조건">
              <span className="text-2xs text-tm-muted">복합 조건 (선택) — 이 이벤트들도 함께 감지됐을 때만</span>
              <div className="flex flex-wrap items-center gap-1.5">
                {EVENT_OPTIONS.filter((o) => o.value !== eventType).map((o) => (
                  <Chip key={o.value} active={required.includes(o.value)} onClick={() => toggleRequired(o.value)}>+ {o.label}</Chip>
                ))}
                {required.some((t) => t !== eventType) && (
                  <SelectBox aria-label="복합 조건 시간 창" value={windowSec} onChange={(e) => setWindowSec(Number(e.target.value))}>
                    {WINDOW_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label} 안에</option>)}
                  </SelectBox>
                )}
              </div>
            </div>
          </>
        )}
      </Step>

      <Step n={2} title="어떤 종목에서">
        {groupMissing && !targetChanged && (
          <Notice tone="warn">
            대상 관심종목 그룹이 삭제되어 이 규칙은 꺼져 있습니다. 아래에서 <b className="text-dracula-fg">새 종목이나 그룹을 고르면</b> 저장할 때 다시 켤 수 있습니다.
          </Notice>
        )}
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="규칙 대상">
          <Chip active={target === "STOCK"} onClick={() => setTarget("STOCK")}>종목 하나</Chip>
          <Chip active={target === "GROUP"} onClick={() => setTarget("GROUP")}>관심종목 그룹</Chip>
        </div>
        {target === "GROUP" ? (
          <>
            <SelectBox label="관심종목 그룹" aria-label="관심종목 그룹" value={groupId ?? ""} onChange={(e) => setGroupId(Number(e.target.value))} disabled={!groups?.length}>
              {groups == null && <option value="">불러오는 중...</option>}
              {groups?.length === 0 && <option value="">관심종목 그룹이 없습니다</option>}
              {groupMissing && rule.targetGroupId != null && groupId === rule.targetGroupId && (
                <option value={rule.targetGroupId} disabled>삭제된 그룹 — 새 그룹을 고르세요</option>
              )}
              {groups?.map((g) => <option key={g.id} value={g.id}>{g.name} · {g.count}종목</option>)}
            </SelectBox>
            <span className="text-2xs text-tm-muted">
              감지 시점에 그룹에 들어 있는 종목마다 판정합니다(나중에 넣은 종목도 포함). 쿨다운·하루 최대 발동은 종목별이 아니라
              <b className="text-tm-soft"> 규칙 하나에 대해</b> 셉니다. 그룹을 지우면 규칙은 꺼집니다.
            </span>
          </>
        ) : (
        <>
        <div className="flex flex-wrap gap-1.5">
          {stock && (
            <Chip active>
              {stock.name} {stock.symbol && <span className="num text-tm-muted">{stock.symbol}</span>}
              <button type="button" onClick={reset} aria-label={`${stock.name} 선택 해제`} className="-mr-1 grid h-4 w-4 place-items-center text-tm-muted hover:text-dracula-fg">
                <Icon name="x" size={12} strokeWidth={2.4} />
              </button>
            </Chip>
          )}
        </div>
        {!stock && (
          <div className="relative">
            <label className="flex h-10 items-center gap-2.5 rounded-lg border border-tm-line bg-tm-inner px-3">
              <Icon name="search" size={16} className="text-tm-muted" />
              <input
                aria-label="종목"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="종목명 또는 코드 검색"
                className="min-w-0 flex-1 bg-transparent text-sm text-dracula-fg outline-none placeholder:text-[#8b92b8]"
              />
            </label>
            {results.length > 0 && (
              <ul className="absolute z-10 m-0 mt-1 w-full list-none overflow-hidden rounded-[10px] border border-tm-line2 bg-tm-panel p-0 shadow-glow-line">
                {results.map((hit) => (
                  <li key={hit.id}>
                    <button
                      type="button"
                      onClick={() => { setStock(hit); setQuery(""); setResults([]); }}
                      className="w-full px-3 py-2 text-left text-13 text-dracula-fg hover:bg-tm-raised"
                    >
                      {hit.name} <span className="num text-2xs text-tm-muted">{hit.symbol}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
        </>
        )}
        {groupMissing && targetChanged && !rule.isActive && (
          <Checkbox
            checked={reactivate}
            onChange={setReactivate}
            label="저장하면서 규칙 다시 켜기"
            sub="대상을 새로 고른 경우에만 다시 켤 수 있습니다. 끄면 대상만 바꾸고 꺼진 채로 둡니다."
          />
        )}
      </Step>

      <Step n={3} title="어떻게 주문할까요">
        {editing ? (
          <p className="m-0 text-13 text-tm-soft">
            <span className={side === "BUY" ? "text-up" : "text-down"}>{side === "BUY" ? "매수" : "매도"}</span>
            <span className="text-2xs text-tm-muted"> · 매수/매도는 수정할 수 없습니다</span>
          </p>
        ) : (
          <BuySell value={side} onChange={setSide} />
        )}
        <div className="flex gap-2">
          <SelectBox label="주문 유형" value={orderType} onChange={(e) => setOrderType(e.target.value as WatchRuleOrderType)} className="flex-1" aria-label="주문 유형">
            <option value="MARKET">시장가</option>
            <option value="LIMIT">지정가 (발동 가격 대비)</option>
          </SelectBox>
          {orderType === "LIMIT" && (
            <Field label="가격 대비" aria-label="지정가 오프셋 (bp)" unit="bp" type="number" min={-MAX_OFFSET_BPS} max={MAX_OFFSET_BPS} step={10} value={offsetBps} onChange={(e) => setOffsetBps(e.target.value)} />
          )}
        </div>
        {orderType === "LIMIT" && (
          <span className="text-2xs text-tm-muted">
            발동 시점 가격의 {offsetBps.trim() && Number.isInteger(Number(offsetBps)) ? offsetLabel(Number(offsetBps)) : "—"}에 지정가를 겁니다(100bp = 1%, 최대 ±10%).
            바로 체결되지 않으면 미체결로 남아 가격이 닿을 때 체결되고, 매수는 그동안 지정가 × 수량만큼 현금이 묶입니다.
          </span>
        )}
        <div className="flex gap-2">
          <SelectBox label="수량 기준" value={sizeType} onChange={(e) => setSizeType(e.target.value as WatchRuleSizeType)} className="flex-1" aria-label="수량 기준">
            <option value="SHARES">주 수</option>
            <option value="EQUITY_PCT">계좌 평가자산 %</option>
          </SelectBox>
          {sizeType === "SHARES" ? (
            <Field label="수량" aria-label="수량 (주)" unit="주" type="number" min={1} max={MAX_QUANTITY} value={quantity} onChange={(e) => setQuantity(e.target.value)} />
          ) : (
            <Field label="계좌 비율" aria-label="계좌 비율 (%)" unit="%" type="number" min={MIN_EQUITY_PCT} max={MAX_EQUITY_PCT} step={0.5} value={equityPct} onChange={(e) => setEquityPct(e.target.value)} />
          )}
        </div>
        {sizeType === "EQUITY_PCT" && (
          <span className="text-2xs text-tm-muted">
            발동 시점 모의 계좌 평가자산(현금 + 미체결 예약금 + 보유 평가액)의 이 비율만큼을 단가로 나눠 주 단위로 내림합니다. 1주도 안 되면 주문하지 않고 이유를 남깁니다.
          </span>
        )}
      </Step>

      <Step n={4} title="안전장치">
        <div className="flex gap-2">
          <Field label="하루 최대 발동" unit="회" placeholder="제한 없음" type="number" min={1} max={1000} value={dailyLimit} onChange={(e) => setDailyLimit(e.target.value)} aria-label="하루 최대 발동" />
          <SelectBox label="쿨다운" aria-label="쿨다운" value={cooldownSec} onChange={(e) => setCooldownSec(Number(e.target.value))} className="flex-1">
            {cooldownOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </SelectBox>
        </div>
        <span className="text-2xs text-tm-muted">
          한 번 체결되면 쿨다운 동안 다시 발동하지 않습니다. 하루(한국 시간) 최대 체결 횟수를 넘으면 서버가 발동을 건너뜁니다.
          {editing && " 비워 두면 제한이 없어집니다. 수정해도 오늘 발동 수·쿨다운·발동 기록은 이어집니다."}
        </span>
        {/* 자동 주문도 항상 리스크 게이트를 통과해야 한다(ADR-051) — 끌 수 없으므로 고정 표시 */}
        <Checkbox checked disabled label="리스크 한도 체크를 통과한 경우에만 주문" sub="자동 주문은 항상 리스크 한도를 통과해야 합니다 (끌 수 없음)" />
      </Step>

      {error && <p role="alert" className="m-0 text-13 text-[#ff8a8a]">{error}</p>}
      {!error && edit?.serverError && <p role="alert" className="m-0 text-13 text-[#ff8a8a]">{edit.serverError}</p>}

      {edit ? (
        <div className="flex gap-2">
          <Btn kind="ghost" size="lg" onClick={edit.onCancel} disabled={submitting}>취소</Btn>
          <Btn kind="primary" size="lg" full onClick={submit} disabled={submitting}>
            {submitting ? "저장 중..." : "변경 저장"}
          </Btn>
        </div>
      ) : (
        <Btn kind="primary" size="lg" full onClick={submit} disabled={submitting}>
          {submitting ? "저장 중..." : "규칙 저장"}
        </Btn>
      )}
    </div>
  );
}
