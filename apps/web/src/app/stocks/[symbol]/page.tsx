interface Props {
  params: { symbol: string };
}

export default function StockDetailPage({ params }: Props) {
  return (
    <div className="max-w-4xl mx-auto p-6">
      <h1 className="text-2xl font-bold mb-2">{params.symbol}</h1>
      <p className="text-gray-500 mb-8">종목 상세 — 차트 및 이벤트 타임라인 (Week 4에서 구현)</p>

      <div className="grid grid-cols-1 gap-4">
        <div className="border border-gray-200 rounded-lg p-4 h-64 flex items-center justify-center text-gray-400">
          차트 영역 (placeholder)
        </div>
        <div className="border border-gray-200 rounded-lg p-4 h-48 flex items-center justify-center text-gray-400">
          이벤트 타임라인 (placeholder)
        </div>
      </div>
    </div>
  );
}
