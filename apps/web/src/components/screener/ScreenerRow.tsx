import Link from "next/link";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import { Icon } from "@/components/terminal";
import BuySellBar from "./BuySellBar";
import ChangeRateBadge from "./ChangeRateBadge";
import AmountLabel from "./AmountLabel";
import { useWatchlistIds } from "@/hooks/useWatchlistIds";
import type { ScreenerItem } from "@/hooks/useScreener";
import type { ColumnKey, ScreenerColumn } from "./ScreenerTable";

interface Props { item: ScreenerItem; columns: ScreenerColumn[]; }

const Dash = () => <span className="text-tm-muted">—</span>;

/**
 * div 기반 행 — TanStack Virtual의 absolute 포지셔닝과 호환.
 * 헤더와 같은 columns 배열(폭 클래스 공유)로 그려서 정렬이 어긋나지 않는다.
 */
export default function ScreenerRow({ item, columns }: Props) {
  const isKR = ["KOSPI", "KOSDAQ"].includes(item.market);
  const { isLoggedIn, isWatched, toggle } = useWatchlistIds();
  const watched = isWatched(item.stockId);

  const cell = (key: ColumnKey): ReactNode => {
    switch (key) {
      case "star":
        return isLoggedIn ? (
          <button
            type="button"
            onClick={() => toggle(item.stockId)}
            aria-label={watched ? `${item.name} 관심종목 해제` : `${item.name} 관심종목 추가`}
            aria-pressed={watched}
            className={cn("grid place-items-center rounded p-0.5 hover:bg-tm-raised", watched ? "text-dracula-yellow" : "text-tm-muted hover:text-dracula-fg")}
          >
            <Icon name="star" size={16} fill={watched ? "currentColor" : "none"} />
          </button>
        ) : (
          <Link href="/login" aria-label={`로그인하고 ${item.name} 관심종목 추가`} className="grid place-items-center text-tm-muted hover:text-dracula-fg">
            <Icon name="star" size={16} />
          </Link>
        );
      case "name":
        return (
          <Link href={`/stocks/${item.symbol}`} className="flex min-w-0 items-center gap-2.5 text-dracula-fg hover:text-dracula-fg">
            <span className="grid h-7 w-7 flex-none place-items-center rounded-lg bg-tm-raised text-xs font-bold text-tm-soft">{item.name.slice(0, 1)}</span>
            <span className="flex min-w-0 flex-col gap-px">
              <span className="truncate font-semibold group-hover:text-dracula-purple">{item.name}</span>
              <span className="num text-2xs text-tm-muted">{item.symbol} · {item.market}</span>
            </span>
          </Link>
        );
      case "price":
        return <span className="num font-medium">{isKR ? "₩" : "$"}{item.price.toLocaleString("ko-KR")}</span>;
      case "change":
        return <ChangeRateBadge rate={item.changeRate} amount={item.changeAmount} />;
      // 시안 컬럼 중 아직 데이터가 없는 것 — 가짜 숫자를 보여주지 않는다(docs/design-rollout-plan.md)
      case "volMult":
      case "today":
      case "event":
        return <Dash />;
      case "marketCap":
        return (
          <span className="flex items-center gap-1">
            {item.isFundamentalsMocked && (
              <span title="KIS API 미설정 또는 응답 없음 — 모의 데이터" className="text-dracula-orange">
                <Icon name="info" size={11} aria-hidden />
              </span>
            )}
            {item.marketCap != null ? <AmountLabel value={item.marketCap} /> : <Dash />}
          </span>
        );
      case "sector":
        return item.sector ? <span className="truncate text-tm-soft">{item.sector}</span> : <Dash />;
      case "amount":
        return <AmountLabel value={item.amount} />;
      case "buySell":
        return <BuySellBar buy={item.buyRatio} sell={item.sellRatio} />;
      case "per":
        return <span className="num">{item.per != null ? item.per.toFixed(2) : "-"}</span>;
      case "pbr":
        return <span className="num">{item.pbr != null ? item.pbr.toFixed(2) : "-"}</span>;
      case "actions":
        return (
          <span className="flex items-center gap-2">
            {isLoggedIn && (
              <Link
                href={`/stocks/${item.symbol}?openAlert=1`}
                aria-label={`${item.name} 알림 만들기`}
                className="grid place-items-center rounded p-0.5 text-tm-muted hover:bg-tm-raised hover:text-dracula-fg"
              >
                <Icon name="bell" size={15} aria-hidden />
              </Link>
            )}
            <Link href={`/stocks/${item.symbol}`} className="text-xs text-dracula-purple hover:underline" aria-label={`${item.name} 차트`}>
              차트
            </Link>
          </span>
        );
    }
  };

  return (
    <div role="row" className="group flex h-[46px] items-center border-b border-tm-line text-13 text-dracula-fg transition-colors hover:bg-tm-raised/60">
      {columns.map((c) => (
        <div key={c.key} role="cell" className={cn("flex min-w-0 shrink-0 items-center whitespace-nowrap px-2.5", c.width)}>
          {cell(c.key)}
        </div>
      ))}
    </div>
  );
}
