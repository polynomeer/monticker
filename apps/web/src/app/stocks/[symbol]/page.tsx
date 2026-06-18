import PriceCard from "@/components/stock/PriceCard";
import EventTimeline from "@/components/stock/EventTimeline";

interface Props {
  params: Promise<{ symbol: string }>;
}

async function resolveStockId(symbol: string): Promise<number | null> {
  try {
    const res = await fetch(
      `${process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"}/api/stocks/search?query=${encodeURIComponent(symbol)}`,
      { cache: "no-store" }
    );
    if (!res.ok) return null;
    const stocks: { id: number; symbol: string }[] = await res.json();
    return stocks.find((s) => s.symbol === symbol)?.id ?? null;
  } catch {
    return null;
  }
}

export default async function StockDetailPage({ params }: Props) {
  const { symbol } = await params;
  const stockId = await resolveStockId(symbol);

  return (
    <div className="max-w-4xl mx-auto p-6">
      <h1 className="text-2xl font-bold mb-6">{symbol}</h1>

      <div className="grid grid-cols-1 gap-4">
        <PriceCard symbol={symbol} />

        <div className="border border-gray-200 rounded-lg p-4 h-64 flex items-center justify-center text-gray-400">
          차트 영역 (Week 5에서 구현)
        </div>

        {stockId ? (
          <EventTimeline stockId={stockId} />
        ) : (
          <div className="border border-gray-200 rounded-lg p-4 text-gray-400 text-sm">
            이벤트 타임라인을 불러올 수 없습니다.
          </div>
        )}
      </div>
    </div>
  );
}
