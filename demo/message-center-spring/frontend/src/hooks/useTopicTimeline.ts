import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { discardTopic, fetchContactTopics, mergeTopics, retryTopicGeneration, updateTopic } from '../api/endpoints';
import { useSse } from './useSse';
import type { MergeTopicsRequest, UpdateTopicRequest } from '../api/types';

export function useTopicTimeline(contactId?: string) {
  const qc = useQueryClient();
  const query = useQuery({
    queryKey: ['contact-topics', contactId],
    queryFn: () => fetchContactTopics(contactId!),
    enabled: Boolean(contactId),
  });
  const invalidate = () => qc.invalidateQueries({ queryKey: ['contact-topics', contactId] });
  useSse(invalidate, ['topic-snapshot-completed']);
  const update = useMutation({ mutationFn: ({ topicId, data }: { topicId: string; data: UpdateTopicRequest }) => updateTopic(topicId, data) });
  const merge = useMutation({ mutationFn: (data: MergeTopicsRequest) => mergeTopics(data) });
  const discard = useMutation({ mutationFn: ({ topicId }: { topicId: string }) => discardTopic(topicId, contactId!) });
  const retry = useMutation({ mutationFn: () => retryTopicGeneration(contactId!) });
  return { ...query, update, merge, discard, retry };
}
