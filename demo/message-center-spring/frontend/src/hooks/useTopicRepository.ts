import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { approveTopicStore, fetchContact, fetchTopicInboxRequests, fetchTopicRepository, rejectTopicStore, restoreTopic } from '../api/endpoints';
import type { TopicProjection } from '../api/types';
import { useSse } from './useSse';
import { contactDisplayName } from '../utils/contactDisplayName';

export function useTopicRepository(search: string, page: number, size = 20, ownerType?: string) {
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['topic-repository', search, ownerType, page, size], queryFn: () => fetchTopicRepository({ search, ownerType, page, size }) });
  const records = (query.data?.records ?? []) as TopicProjection[];
  const contactQueries = useQueries({ queries: records.map(topic => ({
    queryKey: ['topic-repository-contact', topic.contactId],
    queryFn: () => fetchContact(topic.contactId!),
    enabled: !!topic.contactId && !topic.contactRemark && !topic.contactChannelNickname,
    staleTime: 60_000,
  })) });
  const enrichedRecords = records.map((topic, index) => {
    const contact = contactQueries[index]?.data;
    if (!contact) return topic;
    const supported = contact.identities?.filter(identity => ['chatapp', 'email', 'phone'].includes(identity.channelType));
    const matching = supported?.find(identity => topic.channels?.includes(identity.channelType)) ?? supported?.[0];
    return {
      ...topic,
      contactName: topic.contactName || contactDisplayName(contact),
      contactRemark: topic.contactRemark || contact.remark,
      contactChannelType: topic.contactChannelType || matching?.channelType,
      contactChannelNickname: topic.contactChannelNickname || matching?.displayName || matching?.identityValue,
    };
  });
  const restore = useMutation({ mutationFn: ({ topicId }: { topicId: string; contactId?: string }) => restoreTopic(topicId) });
  const requests = useQuery({ queryKey: ['topic-inbox-requests'], queryFn: fetchTopicInboxRequests });
  const approve = useMutation({ mutationFn: ({ requestId }: { requestId: string }) => approveTopicStore(requestId) });
  const reject = useMutation({ mutationFn: ({ requestId, reason }: { requestId: string; reason?: string }) => rejectTopicStore(requestId, reason) });
  useSse(() => { qc.invalidateQueries({ queryKey: ['topic-repository'] }); qc.invalidateQueries({ queryKey: ['topic-inbox-requests'] }); }, ['topic-snapshot-completed']);
  return { ...query, data: query.data ? { ...query.data, records: enrichedRecords } : query.data, restore, requests, approve, reject };
}
