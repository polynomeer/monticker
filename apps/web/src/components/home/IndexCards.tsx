"use client";

import { Pill, Sparkline, Tile, dirClass, fmtPct, fmtSigned } from "@/components/terminal";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";
import { fmtIndexValue, kstTime, useMarketIndices } from "./data";

// 시안의 지수 카드(KOSPI·KOSDAQ·USD/KRW). 값은 GET /api/market/indices(ADR-071).
const SLOTS = [
  { code: "KOSPI", label: "KOSPI" },
  { code: "KOSDAQ", label: "KOSDAQ" },
  { code: "USDKRW", label: "USD/KRW" },
];

/** 개발용 모의 공급자 값 표식 — 실시세처럼 보이지 않게 한다. */
export function MockTag() {
  return (
    <span title="실시세 공급자가 연결되지 않아 개발용 모의 값입니다(ADR-071)">
      <Pill tone="orange" className="h-[18px] px-1.5 text-[0.625rem]">모의</Pill>
    </span>
  );
}

export default function IndexCards() {
  const { data = [], isLoading } = useMarketIndices();
  const theme = useThemeStore((s) => CHART_THEMES[s.chartTheme]);
  const byCode = new Map(data.map((d) => [d.code, d]));

  return (
    <div className="grid gap-2.5" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(220px,1fr))" }}>
      {SLOTS.map(({ code, label }) => {
        const ix = byCode.get(code);
        const rate = ix?.changeRate ?? null;
        const color = rate == null ? "#a4abcf" : rate >= 0 ? theme.upColor : theme.downColor;
        return (
          <Tile key={code}>
            <div className="flex items-start justify-between gap-2">
              <div className="flex min-w-0 flex-col gap-1">
                <span className="flex items-center gap-1.5 text-xs text-tm-muted">
                  {label}
                  {ix?.isMocked && <MockTag />}
                </span>
                {isLoading ? (
                  <span className="h-7 w-28 animate-pulse rounded bg-tm-inner" />
                ) : ix ? (
                  <>
                    <span className="num text-xl font-semibold">{fmtIndexValue(ix.value)}</span>
                    <span className={`num text-xs ${dirClass(rate)}`}>
                      {ix.change == null ? "—" : fmtSigned(ix.change, 2)} ({fmtPct(rate)})
                    </span>
                    <span className="num text-2xs text-tm-muted">{kstTime(ix.asOf)} 기준</span>
                  </>
                ) : (
                  <>
                    <span className="num text-xl font-semibold text-tm-muted">—</span>
                    <span className="text-xs text-tm-muted">시세 수집 전입니다</span>
                  </>
                )}
              </div>
              {ix && ix.closes.length > 1 && (
                <Sparkline values={ix.closes} color={color} width={88} height={36} />
              )}
            </div>
          </Tile>
        );
      })}
    </div>
  );
}
