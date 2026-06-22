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

  if (loading) return <div className="p-6 text-gray-500 dark:text-[#848e9c]">불러오는 중...</div>;

  return (
    <div className="max-w-2xl mx-auto p-6">
      <h1 className="text-2xl font-bold mb-6 dark:text-[#eaecef]">관심종목</h1>

      <form onSubmit={handleCreateGroup} className="flex gap-2 mb-8">
        <input
          type="text"
          value={newGroupName}
          onChange={(e) => setNewGroupName(e.target.value)}
          placeholder="새 그룹 이름"
          className="flex-1 border border-gray-300 rounded-lg px-4 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 dark:bg-[#1e2329] dark:border-[#2b3240] dark:text-[#eaecef] dark:placeholder-[#848e9c]"
        />
        <button
          type="submit"
          className="bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700 dark:bg-[#f0b90b] dark:text-black dark:hover:bg-[#d4a20a]"
        >
          그룹 추가
        </button>
      </form>

      {groups.length === 0 ? (
        <p className="text-gray-500 dark:text-[#848e9c] text-center py-8">관심종목 그룹이 없습니다.</p>
      ) : (
        <div className="space-y-6">
          {groups.map((group) => (
            <div key={group.id} className="border border-gray-200 dark:border-[#2b3240] dark:bg-[#1e2329] rounded-lg p-4">
              <h2 className="font-semibold text-lg mb-3 dark:text-[#eaecef]">{group.name}</h2>
              {group.items.length === 0 ? (
                <p className="text-gray-400 dark:text-[#848e9c] text-sm">종목이 없습니다.</p>
              ) : (
                <ul className="space-y-2">
                  {group.items.map((item) => (
                    <li key={item.id} className="flex items-center justify-between py-2 border-b border-gray-100 dark:border-[#2b3240] last:border-0">
                      <div>
                        <span className="font-medium dark:text-[#eaecef]">{item.name}</span>
                        <span className="ml-2 text-sm text-gray-500 dark:text-[#848e9c]">{item.symbol}</span>
                      </div>
                      {item.memo && <span className="text-xs text-gray-400 dark:text-[#848e9c]">{item.memo}</span>}
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
