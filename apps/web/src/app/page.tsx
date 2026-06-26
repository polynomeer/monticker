import Link from "next/link";

export default function Home() {
  return (
    <main className="flex min-h-[calc(100vh-3.5rem)] flex-col items-center justify-center px-4">
      <div className="text-center max-w-lg">
        <h1 className="text-5xl font-bold text-[#f8f8f2] mb-3 tracking-tight">
          mont<span className="text-[#bd93f9]">icker</span>
        </h1>
        <p className="text-[#6272a4] text-lg mb-10">
          Event-centric stock observation
        </p>
        <div className="flex flex-col sm:flex-row gap-3 justify-center">
          <Link
            href="/stocks/search"
            className="px-6 py-3 rounded-xl bg-[#bd93f9] text-[#282a36] font-semibold hover:bg-[#ff79c6] transition-colors text-sm"
          >
            종목 검색 →
          </Link>
          <Link
            href="/watchlist"
            className="px-6 py-3 rounded-xl bg-[#44475a] text-[#f8f8f2] font-semibold hover:bg-[#6272a4] transition-colors text-sm"
          >
            관심종목
          </Link>
        </div>
      </div>
    </main>
  );
}
