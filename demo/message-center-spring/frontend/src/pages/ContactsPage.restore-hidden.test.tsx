import '@testing-library/jest-dom/vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ContactsPage from './ContactsPage';

const hooks = vi.hoisted(() => ({
  useUnifiedConversations: vi.fn(),
  useConversationPreference: vi.fn(),
  useRestoreConversation: vi.fn(),
  useMergeContacts: vi.fn(),
  useSse: vi.fn(),
}));

vi.mock('../hooks/useContacts', () => hooks);
vi.mock('../hooks/useSse', () => ({ useSse: hooks.useSse }));
vi.mock('../components/ContactCard', () => ({ default: () => <div>联系人</div> }));

function contact(id: string, displayName: string) {
  return {
    type: 'CONTACT', id, displayName, channelTypes: ['wecom'], lastMessageAt: null,
    lastText: '', messageCount: 0, unreadCount: 0, pinned: false,
  };
}

function conversationList(records: ReturnType<typeof contact>[]) {
  return { data: { records, total: records.length }, isLoading: false };
}

function renderAtContact(contactId: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/conversations/contact/${contactId}`]}>
        <Routes>
          <Route path="/conversations/contact/:contactId" element={<ContactsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ContactsPage restores a hidden conversation when it is opened on purpose', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    hooks.useConversationPreference.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
    hooks.useMergeContacts.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
  });

  it('clears the hidden preference when the opened contact is absent from the list', async () => {
    const restore = vi.fn();
    hooks.useRestoreConversation.mockReturnValue({ mutate: restore });
    hooks.useUnifiedConversations.mockReturnValue(conversationList([contact('other-1', '其他客户')]));

    renderAtContact('hidden-1');

    await waitFor(() => expect(restore).toHaveBeenCalledWith({ targetType: 'CONTACT', targetId: 'hidden-1' }));
    expect(restore).toHaveBeenCalledTimes(1);
  });

  it('leaves preferences untouched when the opened contact is already listed', async () => {
    const restore = vi.fn();
    hooks.useRestoreConversation.mockReturnValue({ mutate: restore });
    hooks.useUnifiedConversations.mockReturnValue(conversationList([contact('visible-1', '可见客户')]));

    renderAtContact('visible-1');

    await waitFor(() => expect(hooks.useUnifiedConversations).toHaveBeenCalled());
    expect(restore).not.toHaveBeenCalled();
  });

  it('does not undo a right-click delete while the same conversation stays open', async () => {
    const restore = vi.fn();
    hooks.useRestoreConversation.mockReturnValue({ mutate: restore });
    hooks.useUnifiedConversations.mockReturnValue(conversationList([contact('open-1', '当前客户')]));

    const view = renderAtContact('open-1');
    await waitFor(() => expect(hooks.useUnifiedConversations).toHaveBeenCalled());

    // The account hides the conversation that is currently open; the list refreshes without it.
    hooks.useUnifiedConversations.mockReturnValue(conversationList([]));
    view.rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={['/conversations/contact/open-1']}>
          <Routes>
            <Route path="/conversations/contact/:contactId" element={<ContactsPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await waitFor(() => expect(hooks.useUnifiedConversations).toHaveBeenCalled());
    expect(restore).not.toHaveBeenCalled();
  });
});
