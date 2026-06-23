import Link from "next/link";
import BuySellBar from "./BuySellBar";
import ChangeRateBadge from "./ChangeRateBadge";
import AmountLabel from "./AmountLabel";
import type { ScreenerItem } from "@/hooks/useScreener";

interface Props { item: ScreenerItem; }

export default function ScreenerRow({ item }: Props) {
  const isKR = ["KOSPI","KOSDAQ"].includes(item.market);

  return (
    <tr className="border-b border-[#44475a]/40 hover:bg-[#44475a]/20 transition-colors">
      {/* 순위 */}
      <td className="py-3 pl-4 pr-2 text-center">
        <span className="text-sm text-[#6272a4] tabular-nums w-6 inline-block text-right">
          {item.rank}
        </span>
      </td>

      {/* 종목명 */}
      <td className="py-3 px-3">
        <Link href={`/stocks/${item.symbol}`} className="flex items-center gap-2 hover:opacity-80">
          <div className="w-8 h-8 rounded-full bg-[#44475a] flex items-center justify-center shrink-0">
            <span className="text-[10px] font-bold text-[#f8f8f2]">
              {item.name.slice(0, 2)}
            </span>
          </div>
          <div className="min-w-0">
            <p className="text-sm font-medium dark:text-[#f8f8f2] truncate max-w-[140px]">{item.name}</p>
            <p className="text-[10px] text-[#6272a4]">{item.symbol} · {item.market}</p>
          </div>
        </Link>
      </td>

      {/* 현재가 */}
      <td className="py-3 px-3 text-right">
        <span className="text-sm font-semibold tabular-nums dark:text-[#f8f8f2]">
          {isKR ? "₩" : "$"}{item.price.toLocaleString("ko-KR")}
        </span>
      </td>

      {/* 등락률 */}
      <td className="py-3 px-3 text-right">
        <ChangeRateBadge rate={item.changeRate} amount={item.changeAmount} />
      </td>

      {/* 거래대금 */}
      <td className="py-3 px-3 text-right">
        <AmountLabel value={item.amount} />
      </td>

      {/* 매수비율 */}
      <td className="py-3 px-4 min-w-[120px]">
        <BuySellBar buy={item.buyRatio} sell={item.sellRatio} />
      </td>

      {/* 산업 */}
      <td className="py-3 px-3">
        {item.sector && (
          <span className="text-[10px] px-2 py-1 rounded-full bg-[#44475a] text-[#6272a4] whitespace-nowrap">
            {item.sector}
          </span>
        )}
      </td>
    </tr>
  );
}
