import { useEffect, useRef } from 'react';
import { useAuth } from './useAuth';

export function useSse(onMessage: () => void) {
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

      es.onmessage = () => {
        onMessageRef.current();
      };

      es.addEventListener('templates-changed', () => {
        onMessageRef.current();
      });

      es.addEventListener('message-new', () => {
        onMessageRef.current();
      });

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
