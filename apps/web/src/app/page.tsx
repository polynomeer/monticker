import TopMovers from "@/components/home/TopMovers";

export default function Home() {
  return (
    <main className="max-w-2xl mx-auto p-6">
      <h1 className="text-4xl font-bold mb-2">monticker</h1>
      <p className="text-gray-500 mb-8">Event-centric stock observation</p>
      <TopMovers />
    </main>
  );
}
