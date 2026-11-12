import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  fetchContacts,
  updateContactRemark,
  updateContactTags,
  mergeContacts,
  splitContact,
  toggleConversationPinned,
  hideConversation,
  restoreConversation,
  reorderConversations,
  fetchChannelAddressBook,
  createManualChannelContact,
  deleteManualChannelContact,
} from '../api/endpoints';
import type { ChannelAddressBookChannel, ConversationOrderRequest, ConversationTargetType, SearchMode } from '../api/types';
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
  searchMode: SearchMode = 'contact',
  options?: ContactsQueryOptions,
) {
  const { channelType, channelAccountId } = filters ?? {};
  return useQuery({
    queryKey: ['contacts', search, page, size, channelType, channelAccountId, searchMode],
    queryFn: () => fetchContacts({ search, searchMode, page, size, channelType, channelAccountId }),
    enabled: options?.enabled ?? true,
    placeholderData: channelAccountId ? undefined : (prev) => prev,
  });
}

export function useUnifiedConversations(search?: string, searchMode: SearchMode = 'contact',
                                        options?: ContactsQueryOptions) {
  return useQuery({
    queryKey: ['conversations', search, searchMode],
    queryFn: () => listConversations({ search: search || undefined, searchMode, limit: 50 }),
    enabled: options?.enabled ?? true,
    placeholderData: (prev) => prev,
  });
}

export function useChannelAddressBook(channel: ChannelAddressBookChannel, query?: string,
                                      page = 1, searchMode: SearchMode = 'contact') {
  return useQuery({
    queryKey: ['channel-address-book', channel, query, page, searchMode],
    queryFn: () => fetchChannelAddressBook(channel, { query: query || undefined, searchMode, page, size: 20 }),
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

export function useUpdateContactTags() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, tags }: { id: string; tags: Array<{ name: string; color?: string | null }> }) =>
      updateContactTags(id, tags),
    onSuccess: async (_data, variables) => {
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['contact', variables.id] }),
        qc.invalidateQueries({ queryKey: ['contacts'] }),
        qc.invalidateQueries({ queryKey: ['conversations'] }),
      ]);
    },
  });
}

export function useMergeContacts() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { sourceContactId: string; targetContactId: string }) =>
      mergeContacts(vars.sourceContactId, vars.targetContactId),
    onSuccess: async () => {
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['contacts'] }),
        qc.invalidateQueries({ queryKey: ['conversations'] }),
      ]);
    },
  });
}

export function useSplitContact() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { identityId: string; newContactName?: string }) =>
      splitContact(vars.identityId, vars.newContactName),
    onSuccess: async () => {
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['contacts'] }),
        qc.invalidateQueries({ queryKey: ['conversations'] }),
      ]);
    },
  });
}

export function useConversationPreference() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (vars:
      | { action: 'pin' | 'delete'; targetType: ConversationTargetType; targetId: string }
      | ({ action: 'order' } & ConversationOrderRequest)) => {
      if (vars.action === 'pin') return toggleConversationPinned(vars.targetType, vars.targetId);
      if (vars.action === 'delete') return hideConversation(vars.targetType, vars.targetId);
      if (vars.action === 'order') {
        return reorderConversations({
          sourceType: vars.sourceType,
          sourceId: vars.sourceId,
          targetType: vars.targetType,
          targetId: vars.targetId,
          placement: vars.placement,
        });
      }
    },
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: ['conversations'] }); },
  });
}

/**
 * Deliberately opening a conversation counts as intent to keep it reachable, so a
 * previously stored hidden preference is cleared and the list is refreshed.
 */
export function useRestoreConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { targetType: ConversationTargetType; targetId: string }) =>
      restoreConversation(vars.targetType, vars.targetId),
    onSuccess: async (data) => {
      if (data?.hidden === false) await qc.invalidateQueries({ queryKey: ['conversations'] });
    },
  });
}
