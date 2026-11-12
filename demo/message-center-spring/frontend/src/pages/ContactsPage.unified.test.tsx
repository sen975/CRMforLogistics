import '@testing-library/jest-dom/vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ContactsPage from './ContactsPage';

vi.mock('../hooks/useContacts', () => ({
  useUnifiedConversations: () => ({
    isLoading: false,
    data: {
      total: 2,
      records: [
        { type: 'CONTACT', id: 'c1', displayName: '客户 A', channelTypes: ['email'], lastMessageAt: null, lastText: '', messageCount: 1, unreadCount: 0 },
        { type: 'WECOM_GROUP', id: 'g1', displayName: '内部群', channelTypes: ['wecom'], lastMessageAt: null, lastText: '群消息', messageCount: 2, unreadCount: 0, participantCount: 4, providerConversationKey: 'chat-1' },
      ],
    },
  }),
  useMergeContacts: () => ({ mutateAsync: vi.fn() }),
  useConversationPreference: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useRestoreConversation: () => ({ mutate: vi.fn() }),
}));
vi.mock('../hooks/useSse', () => ({ useSse: vi.fn() }));
vi.mock('../components/ContactCard', () => ({ default: ({ contact }: { contact: { displayName: string } }) => <div>{contact.displayName}</div> }));

describe('ContactsPage unified list', () => {
  it('renders contacts and groups as separate selectable entries', () => {
    const queryClient = new QueryClient();
    render(<QueryClientProvider client={queryClient}><MemoryRouter><ContactsPage /></MemoryRouter></QueryClientProvider>);
    expect(screen.getByText('客户 A')).toBeInTheDocument();
    expect(screen.getByText('内部群')).toBeInTheDocument();
    expect(screen.getByText('4 人')).toBeInTheDocument();
    expect(screen.getByText('内部群').closest('.conversation-group')).toBeInTheDocument();
    expect(screen.getByText('客户 A').closest('.message-center-conversations')).toBeInTheDocument();
  });
});
