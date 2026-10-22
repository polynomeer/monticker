"use client";

import { useState } from "react";
import type { AllocationDimension, DividendSummary, KrwSummary, PortfolioAnalysis, ReturnView } from "@monticker/types";
import { AutoGrid, Bar, KV, Pill, Seg, Tile, dirClass } from "@/components/terminal";
import { EmptyNote } from "@/components/portfolio/PaperStates";
import { ACCOUNT_KIND_LABEL, INSTITUTION_LABEL, fmtMoney } from "./labels";

const won = (v: number | null | undefined) => fmtMoney(v, "KRW");
const signedWon = (v: number | null | undefined) => (v == null ? "—" : `${v > 0 ? "+" : ""}${won(v)}`);

/** 원화로 합친 평가금액과 평가손익 = 주가 손익 + 환차손익(ADR-111) */
export function KrwSummaryTiles({ krw, fxRate }: { krw: KrwSummary; fxRate?: { krwRate: number; mocked: boolean } }) {
  const rate = krw.costBasisKrw && krw.unrealizedPnlKrw != null && krw.costBasisKrw > 0 ? (krw.unrealizedPnlKrw / krw.costBasisKrw) * 100 : null;
  return (
    <AutoGrid min={220}>
      <Tile>
        <span className="flex items-center gap-1.5 text-2xs text-tm-muted">
          원화 환산 평가금액 {krw.fxMocked && <Pill tone="caution">모의 환율</Pill>}
        </span>
        <span className="num text-lg font-semibold">{won(krw.marketValueKrw)}</span>
        <KV k="매입금액(매수일 환율)" v={won(krw.costBasisKrw)} />
        <KV k="평가손익" v={`${signedWon(krw.unrealizedPnlKrw)}${rate == null ? "" : ` (${rate > 0 ? "+" : ""}${rate.toFixed(2)}%)`}`} valueClassName={dirClass(krw.unrealizedPnlKrw)} />
      </Tile>
      <Tile>
        <span className="text-2xs text-tm-muted">평가손익 나누기</span>
        <KV k="주가 손익" v={signedWon(krw.pricePnlKrw)} valueClassName={dirClass(krw.pricePnlKrw)} />
        <KV k="환차손익" v={signedWon(krw.fxPnlKrw)} valueClassName={dirClass(krw.fxPnlKrw)} />
        {fxRate && <KV k="원/달러" v={`${fxRate.krwRate.toLocaleString("ko-KR", { maximumFractionDigits: 2 })}원${fxRate.mocked ? " (모의)" : ""}`} />}
        {krw.fxPnlKrw == null && <span className="text-2xs text-tm-muted">매수한 날의 환율 기록이 없는 종목이 있어 환차손익을 나누지 않았습니다</span>}
      </Tile>
      {(krw.excludedCurrencies.length > 0 || krw.unpricedCount > 0) && (
        <Tile>
          <span className="text-2xs text-tm-muted">합계에서 빠진 것</span>
          {krw.excludedCurrencies.length > 0 && <KV k="환율이 없는 통화" v={krw.excludedCurrencies.join(", ")} mono={false} />}
          {krw.unpricedCount > 0 && <KV k="시세가 없는 종목" v={`${krw.unpricedCount}개`} />}
        </Tile>
      )}
    </AutoGrid>
  );
}

const DIMENSIONS = [
  { value: "SECTOR", label: "업종" },
  { value: "COUNTRY", label: "국가" },
  { value: "CURRENCY", label: "통화" },
  { value: "ACCOUNT_KIND", label: "계좌 종류" },
  { value: "INSTITUTION", label: "증권사" },
] as const;

const COUNTRY_LABEL: Record<string, string> = { KR: "한국", US: "미국", JP: "일본", CN: "중국", HK: "홍콩" };

function sliceLabel(dim: AllocationDimension, key: string): string {
  if (key === "UNCLASSIFIED") return "미분류";
  if (key === "OTHER") return "기타";
  if (dim === "COUNTRY") return COUNTRY_LABEL[key] ?? key;
  if (dim === "ACCOUNT_KIND") return ACCOUNT_KIND_LABEL[key as keyof typeof ACCOUNT_KIND_LABEL] ?? key;
  if (dim === "INSTITUTION") return INSTITUTION_LABEL[key as keyof typeof INSTITUTION_LABEL] ?? key;
  return key;
}

/** 자산 배분 — 원화 평가금액 기준 비중. 한 계열이라 한 색 막대에 이름·비중을 바로 적는다 */
export function AllocationPanel({ analysis }: { analysis: PortfolioAnalysis }) {
  const [dim, setDim] = useState<AllocationDimension>("SECTOR");
  const slices = analysis.allocations.find((a) => a.dimension === dim)?.slices ?? [];
  return (
    <div className="flex flex-col gap-3">
      <Seg options={DIMENSIONS} value={dim} onChange={setDim} size="sm" className="self-start" />
      {slices.length === 0 ? (
        <EmptyNote>시세가 있는 보유 종목이 없습니다.</EmptyNote>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-2.5 p-0" aria-label="자산 배분">
          {slices.map((s) => (
            <li key={s.key} className="flex flex-col gap-1">
              <div className="flex items-baseline gap-2 text-13">
                <span>{sliceLabel(dim, s.key)}</span>
                <span className="num ml-auto text-tm-soft">{won(s.valueKrw)}</span>
                <span className="num w-14 text-right font-semibold">{s.weight.toFixed(1)}%</span>
              </div>
              <Bar pct={s.weight} />
            </li>
          ))}
        </ul>
      )}
      {analysis.krw.fxMocked && <span className="text-2xs text-tm-muted">해외 종목은 모의 환율로 바꿨습니다.</span>}
    </div>
  );
}

/** 최근 12개월 배당 — 달마다 원화(그날 환율) 막대. 마우스를 올리면 그 달의 통화별 금액, 표로도 본다 */
export function DividendsPanel({ dividends }: { dividends: DividendSummary }) {
  const [hover, setHover] = useState<number | null>(null);
  const [table, setTable] = useState(false);
  const max = Math.max(0, ...dividends.months.map((m) => m.krw ?? 0));
  const any = dividends.months.some((m) => Object.keys(m.byCurrency).length > 0);
  const detail = (i: number) => {
    const m = dividends.months[i];
    const parts = Object.entries(m.byCurrency).map(([c, v]) => fmtMoney(v, c));
    return `${m.month} · ${parts.length ? parts.join(" + ") : "없음"}${m.krw != null && parts.some((p) => !p.endsWith("원")) ? ` = ${won(m.krw)}` : ""}${m.krw == null ? " (환율 없음)" : ""}`;
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-x-6 gap-y-1">
        <KV k="최근 12개월(세후)" v={won(dividends.trailing12mKrw)} />
        <KV k="원가 대비 배당률" v={dividends.yieldOnCost == null ? "—" : `${dividends.yieldOnCost.toFixed(2)}%`} />
        <button type="button" className="ml-auto text-xs text-tm-muted hover:text-dracula-fg" onClick={() => setTable(!table)}>
          {table ? "막대로 보기" : "표로 보기"}
        </button>
      </div>
      {!any ? (
        <EmptyNote>최근 12개월에 받은 배당이 없습니다.</EmptyNote>
      ) : table ? (
        <table className="w-full text-13">
          <thead><tr className="text-2xs text-tm-muted"><th scope="col" className="py-1 text-left font-medium">달</th><th scope="col" className="py-1 text-right font-medium">통화별</th><th scope="col" className="py-1 text-right font-medium">원화</th></tr></thead>
          <tbody>
            {dividends.months.map((m) => (
              <tr key={m.month} className="border-t border-tm-line">
                <td className="num py-1">{m.month}</td>
                <td className="num py-1 text-right text-tm-soft">{Object.entries(m.byCurrency).map(([c, v]) => fmtMoney(v, c)).join(" + ") || "—"}</td>
                <td className="num py-1 text-right">{m.krw == null ? "—" : won(m.krw)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="relative">
          <p className="m-0 h-5 text-2xs text-tm-soft" aria-live="polite">{hover == null ? `가장 많은 달 ${won(max)}` : detail(hover)}</p>
          <div className="flex h-28 items-end gap-[2px] border-b border-tm-line" role="list" aria-label="최근 12개월 월별 배당">
            {dividends.months.map((m, i) => {
              const h = max > 0 && m.krw ? Math.max(2, (m.krw / max) * 100) : 0;
              return (
                <div
                  key={m.month}
                  role="listitem"
                  aria-label={detail(i)}
                  tabIndex={0}
                  onMouseEnter={() => setHover(i)}
                  onMouseLeave={() => setHover(null)}
                  onFocus={() => setHover(i)}
                  onBlur={() => setHover(null)}
                  className="flex h-full flex-1 cursor-default items-end outline-hidden focus-visible:bg-tm-raised/60"
                >
                  <div className={`w-full rounded-t-[4px] ${hover === i ? "bg-dracula-pink" : "bg-dracula-purple"}`} style={{ height: `${h}%` }} />
                </div>
              );
            })}
          </div>
          <div className="mt-1 flex gap-[2px] text-[0.625rem] text-tm-muted">
            {dividends.months.map((m, i) => (
              <span key={m.month} className="num flex-1 text-center">{i % 3 === 0 || i === 11 ? `${Number(m.month.slice(5))}월` : ""}</span>
            ))}
          </div>
        </div>
      )}
      {dividends.topStocks.length > 0 && (
        <div className="flex flex-col gap-1">
          <span className="text-2xs text-tm-muted">많이 받은 종목</span>
          {dividends.topStocks.slice(0, 5).map((s) => (
            <KV key={`${s.stockId ?? s.instrumentCode}-${s.currency}`} k={s.name ?? s.instrumentCode ?? "—"} v={fmtMoney(s.trailing12m, s.currency)} />
          ))}
        </div>
      )}
    </div>
  );
}

/** 돈 가중 수익률(XIRR, 연환산) — 통화마다 따로. 입출금은 넣지 않는다 */
export function ReturnsPanel({ returns }: { returns: ReturnView[] }) {
  if (returns.length === 0) return <EmptyNote>거래가 없습니다.</EmptyNote>;
  return (
    <div className="flex flex-col gap-3">
      {returns.map((r) => (
        <div key={r.currency} className="flex flex-col gap-1 rounded-lg bg-tm-inner p-3">
          <div className="flex items-baseline gap-2">
            <span className="text-2xs text-tm-muted">{r.currency} 연환산 수익률</span>
            <span className={`num ml-auto text-15 font-semibold ${dirClass(r.xirr)}`}>{r.xirr == null ? "—" : `${r.xirr > 0 ? "+" : ""}${r.xirr.toFixed(2)}%`}</span>
          </div>
          {r.reason && <span className="text-2xs text-tm-muted">{r.reason}</span>}
          <KV k="낸 돈" v={fmtMoney(r.invested, r.currency)} />
          <KV k="받은 돈(매도·배당)" v={fmtMoney(r.returned, r.currency)} />
          <KV k="지금 평가금액" v={fmtMoney(r.currentValue, r.currency)} />
          {r.multiple != null && <KV k="배수" v={`${r.multiple.toFixed(3)}배`} />}
          {r.since && <span className="text-2xs text-tm-muted">{r.since}부터 · 매수·매도·배당·수수료 흐름 기준(입출금 제외)</span>}
        </div>
      ))}
    </div>
  );
}
