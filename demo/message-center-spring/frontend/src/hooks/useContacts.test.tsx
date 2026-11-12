import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useConversationPreference, useMergeContacts, useSplitContact, useUnifiedConversations } from './useContacts';

const api = vi.hoisted(() => ({
  fetchContacts: vi.fn(),
  listConversations: vi.fn(),
  mergeContacts: vi.fn(),
  splitContact: vi.fn(),
  updateContactRemark: vi.fn(),
  updateContactTags: vi.fn(),
  toggleConversationPinned: vi.fn(),
  hideConversation: vi.fn(),
  reorderConversations: vi.fn(),
}));

vi.mock('../api/endpoints', () => api);

function wrapper(queryClient: QueryClient) {
  return ({ children }: PropsWithChildren) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

describe('contact grouping cache refresh', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('refetches the visible conversation list after contacts are merged', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    api.listConversations
      .mockResolvedValueOnce({ records: [{ type: 'CONTACT', id: 'source' }, { type: 'CONTACT', id: 'target' }], total: 2 })
      .mockResolvedValueOnce({ records: [{ type: 'CONTACT', id: 'target' }], total: 1 });
    api.mergeContacts.mockResolvedValue(undefined);
    const conversations = renderHook(() => useUnifiedConversations(), { wrapper: wrapper(queryClient) });
    const merge = renderHook(() => useMergeContacts(), { wrapper: wrapper(queryClient) });
    await waitFor(() => expect(conversations.result.current.data?.total).toBe(2));

    await merge.result.current.mutateAsync({ sourceContactId: 'source', targetContactId: 'target' });

    await waitFor(() => expect(conversations.result.current.data?.total).toBe(1));
    expect(api.listConversations).toHaveBeenCalledTimes(2);
  });

  it('refetches the visible conversation list after an identity is split', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    api.listConversations
      .mockResolvedValueOnce({ records: [{ type: 'CONTACT', id: 'original' }], total: 1 })
      .mockResolvedValueOnce({ records: [{ type: 'CONTACT', id: 'original' }, { type: 'CONTACT', id: 'new' }], total: 2 });
    api.splitContact.mockResolvedValue(undefined);
    const conversations = renderHook(() => useUnifiedConversations(), { wrapper: wrapper(queryClient) });
    const split = renderHook(() => useSplitContact(), { wrapper: wrapper(queryClient) });
    await waitFor(() => expect(conversations.result.current.data?.total).toBe(1));

    await split.result.current.mutateAsync({ identityId: 'identity', newContactName: '新联系人' });

    await waitFor(() => expect(conversations.result.current.data?.total).toBe(2));
    expect(api.listConversations).toHaveBeenCalledTimes(2);
  });

  it('waits for a hide request to finish before refreshing the visible list', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    api.listConversations
      .mockResolvedValueOnce({ records: [{ type: 'CONTACT', id: 'hidden' }], total: 1 })
      .mockResolvedValueOnce({ records: [], total: 0 });
    let finishHide: (() => void) | undefined;
    api.hideConversation.mockReturnValue(new Promise<void>((resolve) => { finishHide = resolve; }));
    const conversations = renderHook(() => useUnifiedConversations(), { wrapper: wrapper(queryClient) });
    const preference = renderHook(() => useConversationPreference(), { wrapper: wrapper(queryClient) });
    await waitFor(() => expect(conversations.result.current.data?.total).toBe(1));

    const request = preference.result.current.mutateAsync({
      action: 'delete',
      targetType: 'CONTACT',
      targetId: 'hidden',
    });
    await Promise.resolve();
    expect(api.listConversations).toHaveBeenCalledTimes(1);

    finishHide?.();
    await request;
    await waitFor(() => expect(conversations.result.current.data?.total).toBe(0));
    expect(api.listConversations).toHaveBeenCalledTimes(2);
  });
});
