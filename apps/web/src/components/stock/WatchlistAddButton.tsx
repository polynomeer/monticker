"use client";

import { useEffect, useState } from "react";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";

interface Props {
  stockId: number;
}

interface Group {
  id: number;
  name: string;
}

export default function WatchlistAddButton({ stockId }: Props) {
  const [groups, setGroups] = useState<Group[]>([]);
  const [selectedGroup, setSelectedGroup] = useState<number | null>(null);
  const [added, setAdded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [isLoggedIn, setIsLoggedIn] = useState(false);

  useEffect(() => {
    setIsLoggedIn(!!getAccessToken());
  }, []);

  useEffect(() => {
    if (!isLoggedIn) return;
    authFetch("/api/watchlists")
      .then(r => r.ok ? r.json() : [])
      .then((data: Group[]) => {
        setGroups(data);
        if (data.length > 0) setSelectedGroup(data[0].id);
      });
  }, [isLoggedIn]);

  if (!isLoggedIn || groups.length === 0) return null;

  const handleAdd = async () => {
    if (!selectedGroup) return;
    setLoading(true);
    try {
      const res = await authFetch(`/api/watchlists/groups/${selectedGroup}/items`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stockId }),
      });
      if (res.ok || res.status === 409) {
        setAdded(true);
        setTimeout(() => setAdded(false), 2000);
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex gap-2 items-center">
      <select
        value={selectedGroup ?? ""}
        onChange={e => setSelectedGroup(Number(e.target.value))}
        className="border border-gray-200 dark:border-[#44475a] bg-white dark:bg-[#44475a] text-gray-900 dark:text-[#f8f8f2] rounded px-3 py-1.5 text-sm flex-1 transition-colors hover:border-gray-300 dark:hover:border-[#6272a4]"
      >
        {groups.map(g => (
          <option key={g.id} value={g.id}>{g.name}</option>
        ))}
      </select>
      <button
        onClick={handleAdd}
        disabled={loading}
        className="border border-blue-500 dark:border-[#bd93f9] text-blue-600 dark:text-[#bd93f9] rounded px-4 py-1.5 text-sm font-medium hover:bg-blue-50 dark:hover:bg-[#bd93f9]/10 active:scale-[0.98] disabled:opacity-50 disabled:active:scale-100 whitespace-nowrap transition-all duration-150"
      >
        {added ? "추가됨 ✓" : loading ? "추가 중..." : "관심종목 추가"}
      </button>
    </div>
  );
}
