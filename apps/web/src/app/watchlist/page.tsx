"use client";

import { useEffect, useState, useMemo } from "react";
import Link from "next/link";
import { useMarketPrices } from "@/hooks/useMarketPrices";

interface WatchlistItem {
  id: number;
  stockId: number;
  symbol: string;
  name: string;
  memo: string | null;
}

interface WatchlistGroup {
  id: number;
  name: string;
  sortOrder: number;
  items: WatchlistItem[];
}

export default function WatchlistPage() {
  const [groups, setGroups] = useState<WatchlistGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [newGroupName, setNewGroupName] = useState("");

  const fetchGroups = async () => {
    try {
      const res = await fetch("/api/watchlists");
      if (res.ok) setGroups(await res.json());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchGroups(); }, []);

  const stockIds = useMemo(
    () => groups.flatMap((g) => g.items.map((i) => i.stockId)),
    [groups]
  );
  const prices = useMarketPrices(stockIds);

  const handleCreateGroup = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newGroupName.trim()) return;

    const res = await fetch("/api/watchlists/groups", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: newGroupName }),
    });
    if (res.ok) {
      setNewGroupName("");
      fetchGroups();
    }
  };

  if (loading) return <div className="p-6 text-gray-500">불러오는 중...</div>;

  return (
    <div className="max-w-2xl mx-auto p-6">
      <h1 className="text-2xl font-bold mb-6">관심종목</h1>

      <form onSubmit={handleCreateGroup} className="flex gap-2 mb-8">
        <input
          type="text"
          value={newGroupName}
          onChange={(e) => setNewGroupName(e.target.value)}
          placeholder="새 그룹 이름"
          className="flex-1 border border-gray-300 rounded-lg px-4 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <button
          type="submit"
          className="bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700"
        >
          그룹 추가
        </button>
      </form>

      {groups.length === 0 ? (
        <p className="text-gray-500 text-center py-8">관심종목 그룹이 없습니다.</p>
      ) : (
        <div className="space-y-6">
          {groups.map((group) => (
            <div key={group.id} className="border border-gray-200 rounded-lg p-4">
              <h2 className="font-semibold text-lg mb-3">{group.name}</h2>
              {group.items.length === 0 ? (
                <p className="text-gray-400 text-sm">종목이 없습니다.</p>
              ) : (
                <ul className="space-y-2">
                  {group.items.map((item) => {
                    const priceData = prices[item.stockId];
                    const priceStr = priceData
                      ? priceData.price.toLocaleString()
                      : "—";
                    return (
                      <li key={item.id} className="flex items-center justify-between py-2 border-b border-gray-100 last:border-0">
                        <Link
                          href={`/stocks/${item.symbol}`}
                          className="hover:text-blue-600"
                        >
                          <span className="font-medium">{item.name}</span>
                          <span className="ml-2 text-sm text-gray-500">{item.symbol}</span>
                        </Link>
                        <div className="flex items-center gap-3">
                          <span className="font-mono text-sm font-semibold">
                            {priceStr !== "—" ? `₩${priceStr}` : "—"}
                          </span>
                          {item.memo && (
                            <span className="text-xs text-gray-400">{item.memo}</span>
                          )}
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
