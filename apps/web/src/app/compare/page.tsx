import type { Metadata } from "next";
import ComparePage from "@/components/compare/ComparePage";

export const metadata: Metadata = { title: "종목 비교" };

export default function Page() {
  return <ComparePage />;
}
