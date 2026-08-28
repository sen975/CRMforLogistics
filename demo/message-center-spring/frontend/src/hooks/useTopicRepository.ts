import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchTopicRepository, restoreTopic } from '../api/endpoints';
import { useSse } from './useSse';

export function useTopicRepository(search: string, page: number, size = 20) {
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['topic-repository', search, page, size], queryFn: () => fetchTopicRepository({ search, page, size }) });
  useSse(() => qc.invalidateQueries({ queryKey: ['topic-repository'] }), ['topic-snapshot-completed']);
  const restore = useMutation({ mutationFn: ({ topicId, contactId }: { topicId: string; contactId: string }) => restoreTopic(topicId, contactId) });
  return { ...query, restore };
}
