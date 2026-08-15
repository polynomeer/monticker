import Link from "next/link";
import BuySellBar from "./BuySellBar";
import ChangeRateBadge from "./ChangeRateBadge";
import AmountLabel from "./AmountLabel";
import type { ScreenerItem } from "@/hooks/useScreener";

interface Props { item: ScreenerItem; }

/**
 * div 기반 행 — TanStack Virtual의 absolute 포지셔닝과 호환.
 * 헤더의 HEADERS 배열과 width 클래스를 동일하게 맞춰야 정렬됨.
 */
export default function ScreenerRow({ item }: Props) {
  const isKR = ["KOSPI","KOSDAQ"].includes(item.market);

  return (
    <div className="group flex items-center h-[52px] px-2 border-b border-dracula-line/30
                    hover:bg-dracula-purple/[0.04] transition-colors duration-150">

      {/* # 순위 */}
      <div className="w-10 px-2 shrink-0">
        <span className="text-xs text-dracula-comment tabular-nums">{item.rank}</span>
      </div>

      {/* 종목명 */}
      <div className="flex-1 min-w-[160px] px-2">
        <Link href={`/stocks/${item.symbol}`} className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-full bg-dracula-line flex items-center justify-center shrink-0 transition-transform duration-200 group-hover:scale-110">
            <span className="text-[9px] font-bold text-dracula-fg">{item.name.slice(0, 2)}</span>
          </div>
          <div className="min-w-0">
            <p className="text-sm font-medium dark:text-dracula-fg truncate group-hover:text-dracula-purple dark:group-hover:text-dracula-purple transition-colors">{item.name}</p>
            <p className="text-[10px] text-dracula-comment">{item.symbol} · {item.market}</p>
          </div>
        </Link>
      </div>

      {/* 현재가 */}
      <div className="w-28 px-2 text-right shrink-0">
        <span className="text-sm font-semibold tabular-nums dark:text-dracula-fg">
          {isKR ? "₩" : "$"}{item.price.toLocaleString("ko-KR")}
        </span>
      </div>

      {/* 등락률 */}
      <div className="w-28 px-2 text-right shrink-0">
        <ChangeRateBadge rate={item.changeRate} amount={item.changeAmount} />
      </div>

      {/* 거래대금 */}
      <div className="w-24 px-2 text-right shrink-0">
        <AmountLabel value={item.amount} />
      </div>

      {/* 매수/매도 비율 */}
      <div className="w-32 px-2 shrink-0">
        <BuySellBar buy={item.buyRatio} sell={item.sellRatio} />
      </div>

      {/* 산업 */}
      <div className="w-24 px-2 shrink-0">
        {item.sector && (
          <span className="text-[10px] px-2 py-0.5 rounded-full bg-dracula-line text-dracula-comment whitespace-nowrap">
            {item.sector}
          </span>
        )}
      </div>
    </div>
  );
}
