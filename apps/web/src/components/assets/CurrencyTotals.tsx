import type { PortfolioCurrencyTotal } from "@monticker/types";
import { AutoGrid, KV, Tile, dirClass } from "@/components/terminal";
import { fmtMoney } from "./labels";

/** 통화별 합계 — 환율 환산은 다음 단계라 통화를 섞지 않는다(docs/account-aggregation-plan.md) */
export function CurrencyTotals({ totals }: { totals: PortfolioCurrencyTotal[] }) {
  if (totals.length === 0) return null;
  return (
    <AutoGrid min={220}>
      {totals.map((t) => {
        const rate = t.costBasis > 0 && t.unpricedCount === 0 ? (t.unrealizedPnl / t.costBasis) * 100 : null;
        return (
          <Tile key={t.currency}>
            <span className="text-2xs text-tm-muted">{t.currency} 자산</span>
            <span className="num text-lg font-semibold">{fmtMoney(t.marketValue, t.currency)}</span>
            <KV k="매입금액" v={fmtMoney(t.costBasis, t.currency)} />
            <KV
              k="평가손익"
              v={`${t.unrealizedPnl > 0 ? "+" : ""}${fmtMoney(t.unrealizedPnl, t.currency)}${rate == null ? "" : ` (${rate > 0 ? "+" : ""}${rate.toFixed(2)}%)`}`}
              valueClassName={dirClass(t.unrealizedPnl)}
            />
            {t.unpricedCount > 0 && <span className="text-2xs text-tm-muted">시세가 없는 {t.unpricedCount}종목은 평가금액에서 빠졌습니다</span>}
          </Tile>
        );
      })}
    </AutoGrid>
  );
}
