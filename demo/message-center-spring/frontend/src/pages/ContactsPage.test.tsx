import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ContactsPage from './ContactsPage';

const hooks = vi.hoisted(() => ({
  useContacts: vi.fn(),
  useMergeContacts: vi.fn(),
  useSse: vi.fn(),
}));

vi.mock('../hooks/useContacts', () => ({
  useContacts: hooks.useContacts,
  useMergeContacts: hooks.useMergeContacts,
}));
vi.mock('../hooks/useSse', () => ({ useSse: hooks.useSse }));
vi.mock('../components/ContactCard', () => ({ default: () => <div>联系人</div> }));
vi.mock('../components/ContactDetailPanel', () => ({ default: () => <div>详情</div> }));

beforeEach(() => {
  hooks.useContacts.mockReturnValue({ data: { records: [], total: 0, current: 1, pages: 1 }, isLoading: false });
  hooks.useMergeContacts.mockReturnValue({ mutate: vi.fn() });
});

describe('ContactsPage', () => {
  it('keeps the normal CRM contacts view unfiltered', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <ContactsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(screen.getByPlaceholderText('搜索联系人、邮箱、号码')).toBeInTheDocument();
    expect(hooks.useContacts).toHaveBeenCalledWith(undefined);
  });
});
