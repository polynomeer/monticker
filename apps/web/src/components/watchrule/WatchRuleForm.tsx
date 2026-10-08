"use client";

import { useEffect, useState, type ReactNode } from "react";
import type {
  WatchRuleDetectedEventType,
  WatchRuleEventType,
  WatchRuleOrderType,
  WatchRuleSide,
  WatchRuleSizeType,
} from "@monticker/types";
import { Btn, BuySell, Checkbox, Chip, Field, Icon, SelectBox, TextField } from "@/components/terminal";
import { authFetch } from "@/services/api";

interface StockHit { id: number; symbol: string; name: string; }

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
const MAX_QUANTITY = 1_000_000;

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

interface Props {
  onSubmit: (value: WatchRuleFormValue) => void;
  submitting: boolean;
}

function Step({ n, title, children }: { n: number; title: ReactNode; children: ReactNode }) {
  return (
    <fieldset className="m-0 flex min-w-0 flex-col gap-2 border-0 p-0">
      <legend className="mb-2 p-0 text-2xs text-tm-muted">{n}. {title}</legend>
      {children}
    </fieldset>
  );
}

/** 시안 WatchRules "새 규칙" 에디터 — 감지 → 종목 → 주문 → 안전장치 4단계. */
export function WatchRuleForm({ onSubmit, submitting }: Props) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<StockHit[]>([]);
  const [stock, setStock] = useState<StockHit | null>(null);
  const [eventType, setEventType] = useState<WatchRuleEventType>("VOLUME_SURGE");
  const [name, setName] = useState("");
  const [strategies, setStrategies] = useState<StrategyOption[] | null>(null);
  const [ruleSetId, setRuleSetId] = useState("");
  const [signalDirection, setSignalDirection] = useState<WatchRuleSide>("BUY");
  const [required, setRequired] = useState<WatchRuleDetectedEventType[]>([]);
  const [windowSec, setWindowSec] = useState(1800);
  const [dailyLimit, setDailyLimit] = useState("");
  const isSignal = eventType === "QUANT_SIGNAL";
  // ADR-095 — 대상(종목·그룹), 주문 유형, 수량 기준
  const [target, setTarget] = useState<"STOCK" | "GROUP">("STOCK");
  const [groups, setGroups] = useState<GroupOption[] | null>(null);
  const [groupId, setGroupId] = useState<number | null>(null);
  const [orderType, setOrderType] = useState<WatchRuleOrderType>("MARKET");
  const [offsetBps, setOffsetBps] = useState("-50");
  const [sizeType, setSizeType] = useState<WatchRuleSizeType>("SHARES");
  const [equityPct, setEquityPct] = useState("5");

  // 그룹 목록은 "관심종목 그룹"을 고를 때만 불러온다
  useEffect(() => {
    if (target !== "GROUP" || groups) return;
    let cancelled = false;
    loadGroups().then((g) => { if (!cancelled) { setGroups(g); if (g[0]) setGroupId((cur) => cur ?? g[0].id); } });
    return () => { cancelled = true; };
  }, [target, groups]);

  // 전략 목록은 "전략 신호"를 고를 때만 불러온다
  useEffect(() => {
    if (!isSignal || strategies) return;
    let cancelled = false;
    loadStrategies().then((s) => { if (!cancelled) { setStrategies(s); if (s[0]) setRuleSetId((cur) => cur || s[0].id); } });
    return () => { cancelled = true; };
  }, [isSignal, strategies]);
  const [side, setSide] = useState<WatchRuleSide>("BUY");
  const [quantity, setQuantity] = useState("10");
  const [minImportance, setMinImportance] = useState("0");
  const [cooldownSec, setCooldownSec] = useState(600);
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

  const submit = () => {
    setError(null);
    if (target === "STOCK" && !stock) { setError("종목을 선택해주세요."); return; }
    if (target === "GROUP" && groupId == null) { setError("관심종목 그룹을 골라주세요."); return; }
    const qty = Number(quantity);
    if (sizeType === "SHARES") {
      if (!Number.isInteger(qty) || qty < 1) { setError("수량은 1 이상의 정수여야 합니다."); return; }
      if (qty > MAX_QUANTITY) { setError(`수량은 ${MAX_QUANTITY.toLocaleString("ko-KR")}주를 넘을 수 없습니다.`); return; }
    }
    const pct = Number(equityPct);
    if (sizeType === "EQUITY_PCT" && (!Number.isFinite(pct) || pct < MIN_EQUITY_PCT || pct > MAX_EQUITY_PCT || Math.round(pct * 100) !== pct * 100)) {
      setError(`계좌 비율은 ${MIN_EQUITY_PCT}~${MAX_EQUITY_PCT}% 사이(소수점 둘째 자리까지)여야 합니다.`); return;
    }
    const bps = Number(offsetBps);
    if (orderType === "LIMIT" && (!Number.isInteger(bps) || Math.abs(bps) > MAX_OFFSET_BPS)) {
      setError(`지정가 오프셋은 −${MAX_OFFSET_BPS}~${MAX_OFFSET_BPS}bp(±10%) 사이의 정수여야 합니다.`); return;
    }
    const importance = isSignal ? 0 : Number(minImportance);
    if (!Number.isInteger(importance) || importance < 0 || importance > 100) {
      setError("중요도 하한은 0~100 사이의 정수여야 합니다."); return;
    }
    if (isSignal && !ruleSetId) { setError("신호를 받을 전략을 골라주세요."); return; }
    const limit = dailyLimit.trim() ? Number(dailyLimit) : null;
    if (limit != null && (!Number.isInteger(limit) || limit < 1 || limit > 1000)) {
      setError("하루 최대 발동은 1~1000회 사이의 정수여야 합니다."); return;
    }
    const trimmed = name.trim();
    if (trimmed.length > 100) { setError("규칙 이름은 100자 이하여야 합니다."); return; }
    const compound = !isSignal ? required.filter((t) => t !== eventType) : [];
    onSubmit({
      ...(target === "STOCK" && stock ? { stockId: stock.id } : { targetType: "GROUP" as const, targetGroupId: groupId! }),
      eventType, side,
      ...(sizeType === "SHARES" ? { quantity: qty } : { sizeType: "EQUITY_PCT" as const, equityPct: pct }),
      minImportanceScore: importance, cooldownSec,
      ...(trimmed ? { name: trimmed } : {}),
      ...(isSignal ? { ruleSetId, signalDirection } : {}),
      ...(compound.length ? { requiredEventTypes: compound, conditionWindowSec: windowSec } : {}),
      ...(limit != null ? { dailyLimit: limit } : {}),
      ...(orderType === "LIMIT" ? { orderType: "LIMIT" as const, limitOffsetBps: bps } : {}),
    });
  };

  const reset = () => { setStock(null); setQuery(""); setResults([]); };
  const hint = EVENT_OPTIONS.find((o) => o.value === eventType)?.hint;
  const toggleRequired = (t: WatchRuleDetectedEventType) =>
    setRequired((cur) => (cur.includes(t) ? cur.filter((x) => x !== t) : [...cur, t]));

  return (
    <>
      <TextField label="규칙 이름 (선택)" aria-label="규칙 이름" maxLength={100} placeholder="예: 거래량 터지면 10주" value={name} onChange={(e) => setName(e.target.value)} />

      <Step n={1} title="무엇을 감지할까요">
        <SelectBox aria-label="감지할 이벤트" value={eventType} onChange={(e) => setEventType(e.target.value as WatchRuleEventType)}>
          {EVENT_OPTIONS.map((o) => <option key={o.value} value={o.value}>이벤트 · {o.label}</option>)}
          <option value="QUANT_SIGNAL">퀀트랩 · 전략 신호</option>
        </SelectBox>
        {isSignal ? (
          <>
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
            <div className="flex gap-2">
              <Field label="중요도 하한" aria-label="중요도 하한" unit="점 이상" type="number" min={0} max={100} value={minImportance} onChange={(e) => setMinImportance(e.target.value)} />
            </div>
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
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="규칙 대상">
          <Chip active={target === "STOCK"} onClick={() => setTarget("STOCK")}>종목 하나</Chip>
          <Chip active={target === "GROUP"} onClick={() => setTarget("GROUP")}>관심종목 그룹</Chip>
        </div>
        {target === "GROUP" ? (
          <>
            <SelectBox label="관심종목 그룹" aria-label="관심종목 그룹" value={groupId ?? ""} onChange={(e) => setGroupId(Number(e.target.value))} disabled={!groups?.length}>
              {groups == null && <option value="">불러오는 중...</option>}
              {groups?.length === 0 && <option value="">관심종목 그룹이 없습니다</option>}
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
              {stock.name} <span className="num text-tm-muted">{stock.symbol}</span>
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
                id="wr-stock"
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
      </Step>

      <Step n={3} title="어떻게 주문할까요">
        <BuySell value={side} onChange={setSide} />
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
            발동 시점 가격의 {Number.isInteger(Number(offsetBps)) ? offsetLabel(Number(offsetBps)) : "—"}에 지정가를 겁니다(100bp = 1%, 최대 ±10%).
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
            {COOLDOWN_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </SelectBox>
        </div>
        <span className="text-2xs text-tm-muted">한 번 체결되면 쿨다운 동안 다시 발동하지 않습니다. 하루(한국 시간) 최대 체결 횟수를 넘으면 서버가 발동을 건너뜁니다.</span>
        {/* 자동 주문도 항상 리스크 게이트를 통과해야 한다(ADR-051) — 끌 수 없으므로 고정 표시 */}
        <Checkbox checked disabled label="리스크 한도 체크를 통과한 경우에만 주문" sub="자동 주문은 항상 리스크 한도를 통과해야 합니다 (끌 수 없음)" />
      </Step>

      {error && <p role="alert" className="m-0 text-13 text-[#ff8a8a]">{error}</p>}

      <Btn kind="primary" size="lg" full onClick={submit} disabled={submitting}>
        {submitting ? "저장 중..." : "규칙 저장"}
      </Btn>
    </>
  );
}
