"use client";

import { useEffect, useState, type ReactNode } from "react";
import type { WatchRuleEventType, WatchRuleSide } from "@monticker/types";
import { Btn, BuySell, Checkbox, Chip, Field, Icon, PreviewTag, SelectBox } from "@/components/terminal";

interface StockHit { id: number; symbol: string; name: string; }

const EVENT_OPTIONS: { value: WatchRuleEventType; label: string; hint: string }[] = [
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

// 백엔드 검증(quantity > 0)과 별개로 화면에서도 상한을 둔다 — 실수로 1e9 를 넣는 걸 막는다.
const MAX_QUANTITY = 1_000_000;

export interface WatchRuleFormValue {
  stockId: number;
  eventType: WatchRuleEventType;
  side: WatchRuleSide;
  quantity: number;
  minImportanceScore: number;
  cooldownSec: number;
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
    if (!stock) { setError("종목을 선택해주세요."); return; }
    const qty = Number(quantity);
    if (!Number.isInteger(qty) || qty < 1) { setError("수량은 1 이상의 정수여야 합니다."); return; }
    if (qty > MAX_QUANTITY) { setError(`수량은 ${MAX_QUANTITY.toLocaleString("ko-KR")}주를 넘을 수 없습니다.`); return; }
    const importance = Number(minImportance);
    if (!Number.isInteger(importance) || importance < 0 || importance > 100) {
      setError("중요도 하한은 0~100 사이의 정수여야 합니다."); return;
    }
    onSubmit({ stockId: stock.id, eventType, side, quantity: qty, minImportanceScore: importance, cooldownSec });
  };

  const reset = () => { setStock(null); setQuery(""); setResults([]); };
  const hint = EVENT_OPTIONS.find((o) => o.value === eventType)?.hint;

  return (
    <>
      <Step n={1} title="무엇을 감지할까요">
        <SelectBox aria-label="감지할 이벤트" value={eventType} onChange={(e) => setEventType(e.target.value as WatchRuleEventType)}>
          {EVENT_OPTIONS.map((o) => <option key={o.value} value={o.value}>이벤트 · {o.label}</option>)}
        </SelectBox>
        <div className="flex gap-2">
          <Field label="중요도 하한" aria-label="중요도 하한" unit="점 이상" type="number" min={0} max={100} value={minImportance} onChange={(e) => setMinImportance(e.target.value)} />
        </div>
        <span className="text-2xs text-tm-muted">{hint} · 이벤트 강도가 하한 미만이면 발동하지 않습니다 (0이면 모든 이벤트)</span>
      </Step>

      <Step n={2} title="어떤 종목에서">
        <div className="flex flex-wrap gap-1.5">
          {stock && (
            <Chip active>
              {stock.name} <span className="num text-tm-muted">{stock.symbol}</span>
              <button type="button" onClick={reset} aria-label={`${stock.name} 선택 해제`} className="-mr-1 grid h-4 w-4 place-items-center text-tm-muted hover:text-dracula-fg">
                <Icon name="x" size={12} strokeWidth={2.4} />
              </button>
            </Chip>
          )}
          {/* 관심종목 그룹 단위 규칙 — 백엔드 규칙은 아직 종목 하나에만 걸린다 */}
          <span className="inline-flex items-center gap-1.5"><Chip className="opacity-60">+ 종목 그룹</Chip><PreviewTag /></span>
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
      </Step>

      <Step n={3} title="어떻게 주문할까요">
        <BuySell value={side} onChange={setSide} />
        <div className="flex gap-2">
          {/* 자동 주문은 서버에서 항상 시장가로 낸다 */}
          <SelectBox label="주문 유형" value="MARKET" disabled className="flex-1 opacity-70" aria-label="주문 유형">
            <option value="MARKET">시장가</option>
          </SelectBox>
          <Field label="수량" aria-label="수량 (주)" unit="주" type="number" min={1} max={MAX_QUANTITY} value={quantity} onChange={(e) => setQuantity(e.target.value)} />
        </div>
      </Step>

      <Step n={4} title="안전장치">
        <div className="flex gap-2">
          <Field label="하루 최대 발동" unit="회" placeholder="—" disabled className="opacity-60" aria-label="하루 최대 발동 (준비 중)" />
          <SelectBox label="쿨다운" aria-label="쿨다운" value={cooldownSec} onChange={(e) => setCooldownSec(Number(e.target.value))} className="flex-1">
            {COOLDOWN_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </SelectBox>
        </div>
        <span className="text-2xs text-tm-muted">한 번 체결되면 쿨다운 동안 다시 발동하지 않습니다. 하루 최대 발동 횟수 설정은 준비 중입니다.</span>
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
