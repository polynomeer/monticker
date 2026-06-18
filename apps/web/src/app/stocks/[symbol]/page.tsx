import PriceCard from "@/components/stock/PriceCard";

interface Props {
  params: Promise<{ symbol: string }>;
}

export default async function StockDetailPage({ params }: Props) {
  const { symbol } = await params;

  return (
    <div className="max-w-4xl mx-auto p-6">
      <h1 className="text-2xl font-bold mb-6">{symbol}</h1>

      <div className="grid grid-cols-1 gap-4">
        <PriceCard symbol={symbol} />

        <div className="border border-gray-200 rounded-lg p-4 h-64 flex items-center justify-center text-gray-400">
          차트 영역 (Week 4에서 구현)
        </div>

        <div className="border border-gray-200 rounded-lg p-4 h-48 flex items-center justify-center text-gray-400">
          이벤트 타임라인 (Week 4에서 구현)
        </div>
      </div>
    </div>
  );
}
