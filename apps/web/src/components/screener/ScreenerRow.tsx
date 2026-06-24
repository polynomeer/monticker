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
    <div className="flex items-center h-[52px] px-2 border-b border-[#44475a]/30
                    hover:bg-[#44475a]/10 transition-colors">

      {/* # 순위 */}
      <div className="w-10 px-2 shrink-0">
        <span className="text-xs text-[#6272a4] tabular-nums">{item.rank}</span>
      </div>

      {/* 종목명 */}
      <div className="flex-1 min-w-[160px] px-2">
        <Link href={`/stocks/${item.symbol}`} className="flex items-center gap-2 hover:opacity-80">
          <div className="w-7 h-7 rounded-full bg-[#44475a] flex items-center justify-center shrink-0">
            <span className="text-[9px] font-bold text-[#f8f8f2]">{item.name.slice(0, 2)}</span>
          </div>
          <div className="min-w-0">
            <p className="text-sm font-medium dark:text-[#f8f8f2] truncate">{item.name}</p>
            <p className="text-[10px] text-[#6272a4]">{item.symbol} · {item.market}</p>
          </div>
        </Link>
      </div>

      {/* 현재가 */}
      <div className="w-28 px-2 text-right shrink-0">
        <span className="text-sm font-semibold tabular-nums dark:text-[#f8f8f2]">
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
          <span className="text-[10px] px-2 py-0.5 rounded-full bg-[#44475a] text-[#6272a4] whitespace-nowrap">
            {item.sector}
          </span>
        )}
      </div>
    </div>
  );
}
