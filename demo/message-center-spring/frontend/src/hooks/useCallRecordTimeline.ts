import { useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchTimeline } from '../api/endpoints';
import type { TimelineResponse } from '../api/types';
import {
  CALL_RECORD_POLL_INTERVAL_MS,
  hasActiveCallRecord,
} from '../utils/callRecordTimeline';

export interface CallRecordItem {
  kind: 'callRecord';
  id: string;
  occurredAt: string;
  direction: string;
  phonePointId: string;
  durationSeconds: number;
  state: string;
  errorCode: string;
  errorMessage: string;
  errorRetryable: boolean;
  attempts: number;
}

function projectCallRecords(timeline: TimelineResponse): CallRecordItem[] {
  return timeline.items
    .filter((item) => item.type === 'callRecord')
    .map((item) => ({
      kind: 'callRecord' as const,
      id: item.payload.id as string,
      occurredAt: item.occurredAt,
      direction: item.payload.direction as string,
      phonePointId: item.payload.phonePointId as string,
      durationSeconds: item.payload.durationSeconds as number,
      state: item.payload.state as string,
      errorCode: (item.payload.errorCode as string) || '',
      errorMessage: (item.payload.errorMessage as string) || '',
      errorRetryable: (item.payload.errorRetryable as boolean) || false,
      attempts: (item.payload.attempts as number) || 0,
    }));
}

export function useCallRecordTimeline(contactId?: string) {
  const { data = [], refetch } = useQuery<CallRecordItem[]>({
    queryKey: ['call-records', contactId],
    enabled: Boolean(contactId),
    queryFn: async () => projectCallRecords(
      await fetchTimeline(contactId!, { limit: 100 }),
    ),
    retry: 2,
    retryDelay: (attempt) => Math.min(250 * (2 ** attempt), 2000),
    refetchInterval: (query) => (
      hasActiveCallRecord(query.state.data ?? [])
        ? CALL_RECORD_POLL_INTERVAL_MS
        : false
    ),
    refetchIntervalInBackground: false,
  });

  const refresh = useCallback(async () => {
    if (!contactId) {
      return;
    }
    await refetch();
  }, [contactId, refetch]);

  return { records: data, refresh };
}
