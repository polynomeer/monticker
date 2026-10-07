import StockDetailClient from "@/components/stock/StockDetailClient";
import { BtnLink, Notice, Panel, TerminalPage } from "@/components/terminal";

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
      <TerminalPage title="종목 상세" crumb="트레이딩">
        <Panel tabs={["종목 상세"]} actions={[]} closable={false} className="max-w-xl">
          <Notice tone="warn">종목을 찾을 수 없습니다: {symbol}</Notice>
          <BtnLink href="/stocks/search" kind="ghost" icon="search" className="self-start">종목 검색으로</BtnLink>
        </Panel>
      </TerminalPage>
    );
  }

  return <StockDetailClient stockId={stock.id} symbol={symbol} stockName={stock.name} market={stock.market} />;
}
