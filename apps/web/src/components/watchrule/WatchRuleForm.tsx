"use client";

import { useEffect, useState } from "react";
import type { WatchRuleEventType, WatchRuleSide } from "@monticker/types";

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

  return (
    <div className="space-y-4 p-4">
      {/* 종목 */}
      <div>
        <label htmlFor="wr-stock" className="text-sm font-medium text-gray-700 dark:text-dracula-fg">종목</label>
        {stock ? (
          <div className="mt-1.5 flex items-center justify-between rounded-lg border border-gray-300 px-3 py-2 dark:border-dracula-line">
            <span className="text-sm text-gray-900 dark:text-dracula-fg">
              {stock.name} <span className="text-xs text-gray-500 dark:text-dracula-comment">{stock.symbol}</span>
            </span>
            <button type="button" onClick={reset} className="text-xs text-gray-500 hover:underline dark:text-dracula-comment">
              변경
            </button>
          </div>
        ) : (
          <div className="relative mt-1.5">
            <input
              id="wr-stock"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="종목명 또는 코드 검색"
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-dracula-purple focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg dark:placeholder-dracula-comment"
            />
            {results.length > 0 && (
              <ul className="absolute z-10 mt-1 w-full overflow-hidden rounded-lg border border-gray-200 bg-white shadow-lg dark:border-dracula-line dark:bg-dracula-surface">
                {results.map((hit) => (
                  <li key={hit.id}>
                    <button
                      type="button"
                      onClick={() => { setStock(hit); setQuery(""); setResults([]); }}
                      className="w-full px-3 py-2 text-left text-sm text-gray-900 hover:bg-gray-50 dark:text-dracula-fg dark:hover:bg-dracula-line/30"
                    >
                      {hit.name} <span className="text-xs text-gray-500 dark:text-dracula-comment">{hit.symbol}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>

      {/* 이벤트 */}
      <fieldset>
        <legend className="text-sm font-medium text-gray-700 dark:text-dracula-fg">이 이벤트가 감지되면</legend>
        <div className="mt-1.5 grid gap-1.5 sm:grid-cols-3">
          {EVENT_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              onClick={() => setEventType(opt.value)}
              aria-pressed={eventType === opt.value}
              className={`rounded-lg border px-3 py-2 text-left transition-colors ${
                eventType === opt.value
                  ? "border-dracula-purple bg-dracula-purple/10"
                  : "border-gray-300 hover:border-gray-400 dark:border-dracula-line dark:hover:border-dracula-comment"
              }`}
            >
              <span className="block text-sm font-semibold text-gray-900 dark:text-dracula-fg">{opt.label}</span>
              <span className="block text-xs text-gray-500 dark:text-dracula-comment">{opt.hint}</span>
            </button>
          ))}
        </div>
      </fieldset>

      {/* 동작 */}
      <div className="grid gap-3 sm:grid-cols-2">
        <fieldset>
          <legend className="text-sm font-medium text-gray-700 dark:text-dracula-fg">동작</legend>
          <div className="mt-1.5 flex overflow-hidden rounded-lg border border-gray-300 dark:border-dracula-line">
            {(["BUY", "SELL"] as const).map((s) => (
              <button
                key={s}
                type="button"
                onClick={() => setSide(s)}
                aria-pressed={side === s}
                className={`flex-1 py-2 text-sm font-semibold transition-colors ${
                  side === s
                    ? s === "BUY" ? "bg-market-up/20 text-market-up" : "bg-market-down/20 text-market-down"
                    : "text-gray-600 dark:text-dracula-comment"
                }`}
              >
                {s === "BUY" ? "매수" : "매도"}
              </button>
            ))}
          </div>
        </fieldset>
        <div>
          <label htmlFor="wr-qty" className="text-sm font-medium text-gray-700 dark:text-dracula-fg">수량 (주)</label>
          <input
            id="wr-qty"
            type="number"
            min={1}
            max={MAX_QUANTITY}
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            className="mt-1.5 w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-dracula-purple focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg"
          />
        </div>
      </div>

      {/* 조건 */}
      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <label htmlFor="wr-importance" className="text-sm font-medium text-gray-700 dark:text-dracula-fg">
            중요도 하한
          </label>
          <input
            id="wr-importance"
            type="number"
            min={0}
            max={100}
            value={minImportance}
            onChange={(e) => setMinImportance(e.target.value)}
            className="mt-1.5 w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-dracula-purple focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg"
          />
          <p className="mt-1 text-xs text-gray-500 dark:text-dracula-comment">
            이벤트 강도가 이 값 미만이면 발동하지 않습니다 (0이면 모든 이벤트)
          </p>
        </div>
        <div>
          <label htmlFor="wr-cooldown" className="text-sm font-medium text-gray-700 dark:text-dracula-fg">쿨다운</label>
          <select
            id="wr-cooldown"
            value={cooldownSec}
            onChange={(e) => setCooldownSec(Number(e.target.value))}
            className="mt-1.5 w-full cursor-pointer rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-dracula-purple focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg"
          >
            {COOLDOWN_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
          <p className="mt-1 text-xs text-gray-500 dark:text-dracula-comment">
            한 번 체결되면 이 시간 동안 다시 발동하지 않습니다
          </p>
        </div>
      </div>

      {error && <p role="alert" className="text-sm text-market-down">{error}</p>}

      <button
        type="button"
        onClick={submit}
        disabled={submitting}
        className="w-full rounded-lg bg-blue-600 py-2.5 text-sm font-semibold text-white transition-all hover:opacity-90 active:scale-[0.99] disabled:opacity-50 dark:bg-dracula-purple dark:text-dracula-bg"
      >
        {submitting ? "저장 중..." : "규칙 만들기"}
      </button>
    </div>
  );
}
