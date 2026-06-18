import StockDetailClient from "@/components/stock/StockDetailClient";

interface Props {
  params: Promise<{ symbol: string }>;
}

async function resolveStockId(symbol: string): Promise<{ id: number; name: string } | null> {
  try {
    const res = await fetch(
      `${process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"}/api/stocks/search?query=${encodeURIComponent(symbol)}`,
      { cache: "no-store" }
    );
    if (!res.ok) return null;
    const stocks: { id: number; symbol: string; name: string }[] = await res.json();
    const match = stocks.find((s) => s.symbol === symbol);
    return match ? { id: match.id, name: match.name } : null;
  } catch {
    return null;
  }
}

export default async function StockDetailPage({ params }: Props) {
  const { symbol } = await params;
  const stock = await resolveStockId(symbol);

  return (
    <div className="max-w-4xl mx-auto p-6">
      <div className="mb-6">
        <h1 className="text-2xl font-bold">{stock?.name ?? symbol}</h1>
        <p className="text-gray-500 text-sm">{symbol}</p>
      </div>

      {stock ? (
        <StockDetailClient stockId={stock.id} symbol={symbol} />
      ) : (
        <p className="text-gray-400">종목을 찾을 수 없습니다: {symbol}</p>
      )}
    </div>
  );
}
