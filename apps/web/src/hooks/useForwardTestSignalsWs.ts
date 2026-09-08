"use client";

import { useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import type { SignalDirection } from "@monticker/types";

interface ForwardTestSignalEvent {
  type: "SIGNAL";
  direction: SignalDirection;
  stockId: number;
  price: number;
  evalDate: string;
}

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

/** ADR-024 — /topic/rulesets/{ruleSetId}/signals로 발행되는 포워드 테스트 신호를 실시간 구독한다. */
export function useForwardTestSignalsWs(ruleSetId: string | undefined, onSignal: (event: ForwardTestSignalEvent) => void) {
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);
  const onSignalRef = useRef(onSignal);
  onSignalRef.current = onSignal;

  useEffect(() => {
    if (!ruleSetId) return;

    const client = new Client({
      webSocketFactory: () => new SockJS(`${API_BASE}/ws`),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        client.subscribe(`/topic/rulesets/${ruleSetId}/signals`, msg => {
          try {
            const data: ForwardTestSignalEvent = JSON.parse(msg.body);
            onSignalRef.current(data);
          } catch { /* ignore */ }
        });
      },
      onDisconnect: () => setConnected(false),
    });

    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
      clientRef.current = null;
    };
  }, [ruleSetId]);

  return { connected };
}
