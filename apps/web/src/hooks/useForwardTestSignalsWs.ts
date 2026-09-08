"use client";

import { useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import type { SignalDirection } from "@monticker/types";
import { getAccessToken } from "@/services/auth";

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
  const [denied, setDenied] = useState(false);
  const clientRef = useRef<Client | null>(null);
  const onSignalRef = useRef(onSignal);
  onSignalRef.current = onSignal;

  useEffect(() => {
    if (!ruleSetId) return;

    const token = getAccessToken();
    const client = new Client({
      webSocketFactory: () => new SockJS(`${API_BASE}/ws`),
      // ADR-035 — RuleSetSignalAccessInterceptor가 CONNECT의 이 헤더로 신원을 확인한다.
      // 룰셋 소유자·구독자가 아니면 이 토픽 구독은 서버에서 거부된다.
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        setDenied(false);
        client.subscribe(`/topic/rulesets/${ruleSetId}/signals`, msg => {
          try {
            const data: ForwardTestSignalEvent = JSON.parse(msg.body);
            onSignalRef.current(data);
          } catch { /* ignore */ }
        });
      },
      onDisconnect: () => setConnected(false),
      // ADR-035 — SUBSCRIBE가 인터셉터에서 거부되면 CONNECT 성공과 별개로 STOMP ERROR
      // 프레임이 온다. connected는 CONNECT 성공만 반영하므로 별도 상태로 노출한다.
      onStompError: () => {
        setConnected(false);
        setDenied(true);
      },
    });

    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
      clientRef.current = null;
    };
  }, [ruleSetId]);

  return { connected, denied };
}
