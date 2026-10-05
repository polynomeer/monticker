"use client";

import { useEffect, useState } from "react";
import { Icon } from "@/components/terminal";
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
    <div className="flex items-center gap-1.5">
      <select
        aria-label="관심 그룹"
        value={selectedGroup ?? ""}
        onChange={e => setSelectedGroup(Number(e.target.value))}
        className="h-9 max-w-[140px] rounded-lg border border-tm-line2 bg-tm-page px-2.5 text-13 text-dracula-fg outline-none focus:border-dracula-purple [&>option]:bg-tm-panel"
      >
        {groups.map(g => (
          <option key={g.id} value={g.id}>{g.name}</option>
        ))}
      </select>
      <button
        type="button"
        onClick={handleAdd}
        disabled={loading}
        className="inline-flex h-9 items-center gap-1.5 whitespace-nowrap rounded-lg border border-tm-line2 px-3 text-13 font-semibold text-dracula-fg hover:bg-tm-raised disabled:opacity-50"
      >
        {added ? <><Icon name="check" size={14} className="text-dracula-green" /> 추가됨</> : loading ? "추가 중..." : <><Icon name="star" size={14} className="text-dracula-yellow" />관심종목 추가</>}
      </button>
    </div>
  );
}
