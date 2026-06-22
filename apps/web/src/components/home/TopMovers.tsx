"use client";

import { useEffect, useState } from "react";
import Link from "next/link";

interface MoverEvent {
  stockId: number;
  eventType: string;
  title: string;
  importanceScore: number;
  eventTime: string;
}

interface Mover {
  stockId: number;
  title: string;
  topScore: number;
  eventCount: number;
  latestType: string;
}

export default function TopMovers() {
  const [movers, setMovers] = useState<Mover[]>([]);

  useEffect(() => {
    const fetch_ = async () => {
      try {
        const res = await fetch("/api/events/recent?limit=50");
        if (!res.ok) return;
        const events: MoverEvent[] = await res.json();

        // stockId별 집계
        const map = new Map<number, Mover>();
        for (const e of events) {
          if (!["PRICE_SPIKE", "PRICE_DROP", "VOLUME_SURGE"].includes(e.eventType)) continue;
          const existing = map.get(e.stockId);
          if (!existing) {
            map.set(e.stockId, {
              stockId: e.stockId,
              title: e.title,
              topScore: e.importanceScore,
              eventCount: 1,
              latestType: e.eventType,
            });
          } else {
            existing.eventCount++;
            if (e.importanceScore > existing.topScore) {
              existing.topScore = e.importanceScore;
              existing.latestType = e.eventType;
            }
          }
        }
        setMovers(
          [...map.values()]
            .sort((a, b) => b.topScore - a.topScore || b.eventCount - a.eventCount)
            .slice(0, 5)
        );
      } catch { /* ignore */ }
    };
    fetch_();
    const id = setInterval(fetch_, 15_000);
    return () => clearInterval(id);
  }, []);

  if (movers.length === 0) return null;

  const COLOR: Record<string, string> = {
    PRICE_SPIKE:  "text-green-600 bg-green-50",
    PRICE_DROP:   "text-red-600 bg-red-50",
    VOLUME_SURGE: "text-blue-600 bg-blue-50",
  };
  const LABEL: Record<string, string> = {
    PRICE_SPIKE: "급등", PRICE_DROP: "급락", VOLUME_SURGE: "급증",
  };

  return (
    <div className="border border-gray-200 rounded-lg p-4">
      <h2 className="font-semibold mb-3">주목 종목</h2>
      <ul className="space-y-2">
        {movers.map(m => (
          <li key={m.stockId}>
            <Link
              href={`/stocks/${m.stockId}`}
              className="flex items-center justify-between p-2 rounded hover:bg-gray-50"
            >
              <span className="text-sm font-medium line-clamp-1 flex-1">{m.title}</span>
              <div className="flex items-center gap-2 ml-2">
                <span className="text-xs text-gray-400">{m.eventCount}건</span>
                <span className={`text-xs px-2 py-0.5 rounded-full font-semibold ${COLOR[m.latestType] ?? ""}`}>
                  {LABEL[m.latestType] ?? m.latestType}
                </span>
              </div>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
