import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  fetchContacts,
  updateContactRemark,
  mergeContacts,
  splitContact,
} from '../api/endpoints';

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
