import type { Metadata } from "next";
import StockSearch from "@/components/stock/StockSearch";

export const metadata: Metadata = { title: "종목 검색" };

export default function StockSearchPage() {
  return <StockSearch />;
}
