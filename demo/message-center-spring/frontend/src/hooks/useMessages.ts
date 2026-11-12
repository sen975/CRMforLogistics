import { useQuery } from '@tanstack/react-query';
import { fetchThread, fetchMessage } from '../api/endpoints';

export function useThread(contactId: string | undefined, cursor?: string, limit = 10) {
  return useQuery({
    queryKey: ['thread', contactId, cursor, limit],
    queryFn: () => fetchThread(contactId!, { cursor, limit }),
    enabled: !!contactId,
  });
}

export function useMessage(messageId: string | undefined) {
  return useQuery({
    queryKey: ['message', messageId],
    queryFn: () => fetchMessage(messageId!),
    enabled: !!messageId,
  });
}
