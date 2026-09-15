import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ContactsPage from './ContactsPage';

const hooks = vi.hoisted(() => ({
  useContacts: vi.fn(),
  useUnifiedConversations: vi.fn(),
  useMergeContacts: vi.fn(),
  useConversationPreference: vi.fn(),
  useSse: vi.fn(),
}));

vi.mock('../hooks/useContacts', () => ({
  useContacts: hooks.useContacts,
  useUnifiedConversations: hooks.useUnifiedConversations,
  useMergeContacts: hooks.useMergeContacts,
  useConversationPreference: hooks.useConversationPreference,
}));
vi.mock('../hooks/useSse', () => ({ useSse: hooks.useSse }));
vi.mock('../components/ContactCard', () => ({ default: () => <div>联系人</div> }));
vi.mock('../components/ContactDetailPanel', () => ({ default: () => <div>详情</div> }));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <ContactsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ContactsPage search mode', () => {
  beforeEach(() => {
    hooks.useUnifiedConversations.mockReturnValue({ data: { records: [], total: 0, current: 1, pages: 1 }, isLoading: false });
    hooks.useContacts.mockReturnValue({ data: { records: [], total: 0, current: 1, pages: 1 }, isLoading: false });
    hooks.useMergeContacts.mockReturnValue({ mutate: vi.fn() });
    hooks.useConversationPreference.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
  });

  it('switches to tag mode, clears the keyword and asks the server for tags', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByPlaceholderText('搜索联系人、邮箱、号码'), '张三');
    await waitFor(() => expect(hooks.useUnifiedConversations).toHaveBeenLastCalledWith('张三', 'contact'));

    await user.click(screen.getByRole('button', { name: '搜索模式' }));
    await user.click(await screen.findByRole('menuitem', { name: '标签' }));

    const box = await screen.findByPlaceholderText('搜索标签名');
    expect(box).toHaveValue('');
    await waitFor(() => expect(hooks.useUnifiedConversations).toHaveBeenLastCalledWith(undefined, 'tag'));
  });

  it('explains an empty tag search differently from an empty contact list', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(screen.getByText('暂无联系人')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '搜索模式' }));
    await user.click(await screen.findByRole('menuitem', { name: '标签' }));
    await user.type(screen.getByPlaceholderText('搜索标签名'), '客户');

    expect(screen.getByText('没有联系人被打上含「客户」的标签')).toBeInTheDocument();
    expect(screen.queryByText('暂无联系人')).not.toBeInTheDocument();
  });
});
