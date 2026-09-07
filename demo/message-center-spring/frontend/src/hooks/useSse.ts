import { useEffect, useRef } from 'react';
import { useAuth } from './useAuth';

export interface SseEventPayload {
  type: string;
  data: unknown;
}

export function useSse(onMessage: (event?: SseEventPayload) => void, eventNames?: string[]) {
  const { token, isAuthenticated } = useAuth();
  const onMessageRef = useRef(onMessage);
  onMessageRef.current = onMessage;

  useEffect(() => {
    if (!isAuthenticated || !token) return;

    let es: EventSource;
    let reconnectTimer: ReturnType<typeof setTimeout>;
    let retries = 0;
    const maxBackoff = 30_000;

    function connect() {
      es = new EventSource('/api/events');

      es.onopen = () => {
        retries = 0;
      };

      if (!eventNames) es.onmessage = (event) => onMessageRef.current(toPayload('message', event));
      for (const eventName of (eventNames ?? [
        'templates-changed',
        'message-new',
        'broadcast-updated',
        'wecom-group-kind-sync-completed',
      ])) {
        es.addEventListener(eventName, (event) => onMessageRef.current(toPayload(eventName, event)));
      }

      es.onerror = () => {
        es.close();
        const delay = Math.min(1000 * Math.pow(2, Math.min(retries, 5)), maxBackoff);
        retries++;
        reconnectTimer = setTimeout(connect, delay);
      };
    }

    connect();

    return () => {
      es.close();
      clearTimeout(reconnectTimer);
    };
  }, [isAuthenticated, token]);
}

function toPayload(type: string, event: Event): SseEventPayload {
  const raw = (event as MessageEvent<string>).data;
  if (typeof raw !== 'string') return { type, data: {} };
  try {
    return { type, data: JSON.parse(raw) as unknown };
  } catch {
    return { type, data: {} };
  }
}
