import MarketSummary from "@/components/home/MarketSummary";
import TopMovers from "@/components/home/TopMovers";
import RecentEvents from "@/components/home/RecentEvents";
import WatchlistSummary from "@/components/home/WatchlistSummary";
import PortfolioSnapshot from "@/components/home/PortfolioSnapshot";
import Link from "next/link";

export default function Home() {
  return (
    <main className="max-w-5xl mx-auto px-6 py-8 space-y-6">

      {/* 히어로 — 앱 정체성 */}
      <div className="border dark:border-[#44475a] dark:bg-[#1e1f29] rounded-xl p-6">
        <h1 className="text-2xl font-bold dark:text-[#f8f8f2]">
          시장을 <span className="text-blue-600 dark:text-[#bd93f9]">이벤트</span>로 읽다
        </h1>
        <p className="text-sm dark:text-[#6272a4] mt-1">
          가격 급등·거래량 급증·공시를 실시간으로 감지하고 타임라인으로 시각화합니다.
        </p>
        <div className="flex flex-wrap gap-2 mt-4">
          <Link href="/screener"
            className="text-xs px-4 py-2 rounded-full bg-blue-600 dark:bg-[#bd93f9] text-white dark:text-[#282a36] font-semibold hover:opacity-90">
            종목 스크리너 →
          </Link>
          <Link href="/backtest"
            className="text-xs px-4 py-2 rounded-full border dark:border-[#44475a] dark:text-[#6272a4] hover:dark:text-[#f8f8f2] hover:dark:border-[#bd93f9] transition-colors">
            전략 백테스팅
          </Link>
          <Link href="/portfolio"
            className="text-xs px-4 py-2 rounded-full border dark:border-[#44475a] dark:text-[#6272a4] hover:dark:text-[#f8f8f2] hover:dark:border-[#bd93f9] transition-colors">
            모의 투자
          </Link>
        </div>
      </div>

      {/* 2열 레이아웃 */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* 왼쪽 */}
        <div className="space-y-4">
          <MarketSummary />
          <WatchlistSummary />
          <PortfolioSnapshot />
        </div>

        {/* 오른쪽 */}
        <div className="space-y-4">
          <TopMovers />
          <RecentEvents />
        </div>
      </div>
    </main>
  );
}
