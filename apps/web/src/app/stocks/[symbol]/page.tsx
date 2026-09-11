import StockDetailClient from "@/components/stock/StockDetailClient";

interface Props {
  params: Promise<{ symbol: string }>;
}

async function resolveStockId(symbol: string): Promise<{ id: number; name: string; market: string } | null> {
  try {
    const res = await fetch(
      `${process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"}/api/stocks/search?query=${encodeURIComponent(symbol)}`,
      { cache: "no-store" }
    );
    if (!res.ok) return null;
    const stocks: { id: number; symbol: string; name: string; market: string }[] = await res.json();
    const match = stocks.find((s) => s.symbol === symbol);
    return match ? { id: match.id, name: match.name, market: match.market } : null;
  } catch {
    return null;
  }
}

export default async function StockDetailPage({ params }: Props) {
  const { symbol } = await params;
  const stock = await resolveStockId(symbol);

  if (!stock) {
    return (
      <div className="p-6">
        <p className="text-gray-400 dark:text-dracula-comment">종목을 찾을 수 없습니다: {symbol}</p>
      </div>
    );
  }

  return <StockDetailClient stockId={stock.id} symbol={symbol} stockName={stock.name} market={stock.market} />;
}
