"use client";

import { useEffect, useRef, useState } from "react";
import { Client, type StompSubscription } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import type { SignalDirection } from "@monticker/types";
import { getAccessToken } from "@/services/auth";

export interface RuleSetSignalEvent {
  type: "SIGNAL";
  /** 서버가 V76 이후 함께 보낸다. 없으면 토픽에서 채운다. */
  rulesetId?: string;
  direction: SignalDirection;
  stockId: number;
  price: number;
  evalDate: string;
}

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const topic = (id: string) => `/topic/rulesets/${id}/signals`;

/**
 * 여러 룰셋의 신호 토픽(/topic/rulesets/{id}/signals)을 **한 STOMP 연결**로 받는다.
 * 예전에는 구독 전략마다 연결을 하나씩 열었다(전략 N개 = 웹소켓 N개).
 * id 목록이 바뀌면 다시 연결하지 않고 토픽만 더하고 뺀다.
 *
 * ADR-035 — 서버(RuleSetSignalAccessInterceptor)는 소유자·구독자가 아닌 SUBSCRIBE를 거부하고
 * ERROR 프레임을 보낸다. 같은 연결의 다른 토픽까지 끊기므로, 거부되면 재연결을 멈추고 denied로
 * 알린다(되살아나 같은 거부를 5초마다 반복하지 않게). 호출하는 쪽은 구독 중인 id만 넘겨야 한다.
 */
export function useRuleSetSignalsWs(ruleSetIds: string[], onSignal: (rulesetId: string, event: RuleSetSignalEvent) => void) {
  const [connected, setConnected] = useState(false);
  const [denied, setDenied] = useState(false);
  const clientRef = useRef<Client | null>(null);
  const subsRef = useRef(new Map<string, StompSubscription>());
  const onSignalRef = useRef(onSignal);
  onSignalRef.current = onSignal;
  const idsRef = useRef<string[]>([]);
  const key = [...new Set(ruleSetIds)].sort().join(",");
  idsRef.current = key ? key.split(",") : [];
  const active = idsRef.current.length > 0;

  // 토픽 목록을 현재 id에 맞춘다(연결돼 있을 때만).
  const sync = () => {
    const client = clientRef.current;
    if (!client?.connected) return;
    const want = new Set(idsRef.current);
    for (const [id, sub] of subsRef.current) {
      if (!want.has(id)) { sub.unsubscribe(); subsRef.current.delete(id); }
    }
    for (const id of want) {
      if (subsRef.current.has(id)) continue;
      subsRef.current.set(id, client.subscribe(topic(id), (msg) => {
        try {
          const data: RuleSetSignalEvent = JSON.parse(msg.body);
          onSignalRef.current(data.rulesetId ?? id, data);
        } catch { /* ignore */ }
      }));
    }
  };

  useEffect(() => {
    if (!active) return;
    const token = getAccessToken();
    const client = new Client({
      webSocketFactory: () => new SockJS(`${API_BASE}/ws`),
      // ADR-035 — RuleSetSignalAccessInterceptor가 CONNECT의 이 헤더로 신원을 확인한다.
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        setDenied(false);
        subsRef.current.clear();
        sync();
      },
      onDisconnect: () => setConnected(false),
      onWebSocketClose: () => { setConnected(false); subsRef.current.clear(); },
      onStompError: () => {
        setConnected(false);
        setDenied(true);
        subsRef.current.clear();
        void client.deactivate();
      },
    });
    client.activate();
    clientRef.current = client;
    const subs = subsRef.current;
    return () => {
      void client.deactivate();
      clientRef.current = null;
      subs.clear();
    };
    // 연결은 "받을 토픽이 있는가"에만 묶는다 — 토픽 변화는 아래 effect가 처리한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active]);

  useEffect(() => {
    sync();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return { connected, denied };
}
