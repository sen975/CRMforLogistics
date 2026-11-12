import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchWeComGroupTopics, retryWeComGroupTopicGeneration, storeTopic } from '../api/endpoints';
import { useSse } from './useSse';

export function useWeComGroupTopicTimeline(sourceConversationId?: string, enabled = false) {
  const qc = useQueryClient();
  const queryKey = ['wecom-group-topics', sourceConversationId] as const;
  const query = useQuery({
    queryKey,
    queryFn: () => fetchWeComGroupTopics(sourceConversationId!),
    enabled: Boolean(sourceConversationId) && enabled,
  });
  useSse(() => {
    if (sourceConversationId) void qc.invalidateQueries({ queryKey });
  }, ['topic-snapshot-completed']);
  const store = useMutation({ mutationFn: ({ topicId }: { topicId: string }) => storeTopic(topicId) });
  const retry = useMutation({
    mutationFn: () => retryWeComGroupTopicGeneration(sourceConversationId!),
    onSuccess: () => qc.invalidateQueries({ queryKey }),
  });
  return { ...query, store, retry };
}
