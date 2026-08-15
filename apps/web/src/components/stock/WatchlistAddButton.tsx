"use client";

import { useEffect, useState } from "react";
import { Check } from "@phosphor-icons/react";
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
        className="border border-gray-200 dark:border-dracula-line bg-white dark:bg-dracula-line text-gray-900 dark:text-dracula-fg rounded px-3 py-1.5 text-sm flex-1 transition-colors hover:border-gray-300 dark:hover:border-dracula-comment"
      >
        {groups.map(g => (
          <option key={g.id} value={g.id}>{g.name}</option>
        ))}
      </select>
      <button
        onClick={handleAdd}
        disabled={loading}
        className="border border-blue-500 dark:border-dracula-purple text-blue-600 dark:text-dracula-purple rounded px-4 py-1.5 text-sm font-medium hover:bg-blue-50 dark:hover:bg-dracula-purple/10 active:scale-[0.98] disabled:opacity-50 disabled:active:scale-100 whitespace-nowrap transition-all duration-150 inline-flex items-center gap-1"
      >
        {added ? <><Check size={14} weight="bold" aria-hidden /> 추가됨</> : loading ? "추가 중..." : "관심종목 추가"}
      </button>
    </div>
  );
}
