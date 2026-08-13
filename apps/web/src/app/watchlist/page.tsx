"use client";

import { useEffect, useState } from "react";

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

  if (loading) return <div className="p-6 text-gray-500 dark:text-[#6272a4]">불러오는 중...</div>;

  return (
    <div className="max-w-2xl mx-auto p-4 sm:p-6 animate-fade-up">
      <h1 className="text-2xl font-bold tracking-tight mb-6 text-gray-900 dark:text-[#f8f8f2]">관심종목</h1>

      <form onSubmit={handleCreateGroup} className="flex gap-2 mb-8">
        <input
          type="text"
          value={newGroupName}
          onChange={(e) => setNewGroupName(e.target.value)}
          placeholder="새 그룹 이름"
          className="flex-1 border border-gray-300 rounded-lg px-4 py-2 transition-colors hover:border-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500/50 dark:bg-[#282a36] dark:border-[#44475a] dark:text-[#f8f8f2] dark:placeholder-[#6272a4] dark:hover:border-[#6272a4] dark:focus:ring-[#bd93f9]/50"
        />
        <button
          type="submit"
          className="bg-blue-600 text-white px-6 py-2 rounded-lg font-medium hover:bg-blue-700 active:scale-[0.98] transition-all duration-150 dark:bg-[#bd93f9] dark:text-[#282a36] dark:hover:opacity-90"
        >
          그룹 추가
        </button>
      </form>

      {groups.length === 0 ? (
        <p className="text-gray-500 dark:text-[#6272a4] text-center py-8">관심종목 그룹이 없습니다.</p>
      ) : (
        <div className="space-y-6">
          {groups.map((group) => (
            <div key={group.id} className="border border-gray-200 dark:border-[#44475a] bg-white dark:bg-[#282a36] rounded-lg p-4 shadow-sm dark:shadow-glow-line hover:border-gray-300 dark:hover:border-[#6272a4]/60 transition-colors">
              <h2 className="font-semibold text-lg mb-3 text-gray-900 dark:text-[#f8f8f2]">{group.name}</h2>
              {group.items.length === 0 ? (
                <p className="text-gray-400 dark:text-[#6272a4] text-sm">종목이 없습니다.</p>
              ) : (
                <ul className="space-y-2">
                  {group.items.map((item) => (
                    <li key={item.id} className="flex items-center justify-between py-2 border-b border-gray-100 dark:border-[#44475a] last:border-0">
                      <div>
                        <span className="font-medium dark:text-[#f8f8f2]">{item.name}</span>
                        <span className="ml-2 text-sm text-gray-500 dark:text-[#6272a4]">{item.symbol}</span>
                      </div>
                      {item.memo && <span className="text-xs text-gray-400 dark:text-[#6272a4]">{item.memo}</span>}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
