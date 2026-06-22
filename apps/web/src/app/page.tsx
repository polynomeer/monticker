import RecentEvents from "@/components/home/RecentEvents";
import MarketSummary from "@/components/home/MarketSummary";

export default function Home() {
  return (
    <main className="max-w-4xl mx-auto p-6">
      <h1 className="text-2xl font-bold mb-6">시장 현황</h1>
      <div className="grid grid-cols-1 gap-6">
        <MarketSummary />
        <RecentEvents />
      </div>
    </main>
  );
}
