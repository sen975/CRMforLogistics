import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  fetchContacts,
  updateContactRemark,
  mergeContacts,
  splitContact,
  toggleConversationPinned,
  hideConversation,
  reorderConversations,
  fetchChannelAddressBook,
  createManualChannelContact,
  deleteManualChannelContact,
} from '../api/endpoints';
import type { ChannelAddressBookChannel, ConversationOrderRequest, ConversationTargetType } from '../api/types';
import { listConversations } from '../api/endpoints';
import type { ConversationListItem } from '../api/types';

interface ContactFilters {
  channelType?: string;
  channelAccountId?: string;
}

interface ContactsQueryOptions {
  enabled?: boolean;
}

export function useContacts(
  search?: string,
  page = 1,
  size = 20,
  filters?: ContactFilters,
  options?: ContactsQueryOptions,
) {
  const { channelType, channelAccountId } = filters ?? {};
  return useQuery({
    queryKey: ['contacts', search, page, size, channelType, channelAccountId],
    queryFn: () => fetchContacts({ search, page, size, channelType, channelAccountId }),
    enabled: options?.enabled ?? true,
    placeholderData: channelAccountId ? undefined : (prev) => prev,
  });
}

export function useUnifiedConversations(search?: string, options?: ContactsQueryOptions) {
  return useQuery({
    queryKey: ['conversations', search],
    queryFn: () => listConversations({ search: search || undefined, limit: 50 }),
    enabled: options?.enabled ?? true,
    placeholderData: (prev) => prev,
  });
}

export function useChannelAddressBook(channel: ChannelAddressBookChannel, query?: string, page = 1) {
  return useQuery({
    queryKey: ['channel-address-book', channel, query, page],
    queryFn: () => fetchChannelAddressBook(channel, { query: query || undefined, page, size: 20 }),
  });
}

export function useCreateManualChannelContact(channel: ChannelAddressBookChannel) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: { displayName: string; address: string }) => createManualChannelContact(channel, payload),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['channel-address-book', channel] }),
  });
}

export function useDeleteManualChannelContact(channel: ChannelAddressBookChannel) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (contactId: string) => deleteManualChannelContact(channel, contactId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['channel-address-book', channel] }),
  });
}

export type { ConversationListItem };

export function useUpdateContactRemark() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, remark }: { id: string; remark: string }) => updateContactRemark(id, remark),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['contacts'] }),
  });
}

export function useMergeContacts() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { sourceContactId: string; targetContactId: string }) =>
      mergeContacts(vars.sourceContactId, vars.targetContactId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['contacts'] }),
  });
}

export function useSplitContact() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { identityId: string; newContactName?: string }) =>
      splitContact(vars.identityId, vars.newContactName),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['contacts'] }),
  });
}
