import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp, ConfigProvider } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ContactDetailPanel from './ContactDetailPanel';

const fetchContact = vi.fn();
const selectChannel = vi.fn();
const updateTagsMutateAsync = vi.fn();

vi.mock('../api/endpoints', () => ({
  fetchContact: (...args: unknown[]) => fetchContact(...args),
  fetchMessage: vi.fn(),
}));

vi.mock('../hooks/useContacts', () => ({
  useUpdateContactRemark: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useUpdateContactTags: () => ({ mutateAsync: updateTagsMutateAsync, isPending: false }),
  useSplitContact: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

vi.mock('../hooks/useDetailPanel', () => ({
  useDetailPanel: () => ({ selectedDetail: null, selectChannel }),
}));

describe('ContactDetailPanel identity display', () => {
  beforeEach(() => {
    fetchContact.mockReset();
    selectChannel.mockReset();
    updateTagsMutateAsync.mockReset();
    updateTagsMutateAsync.mockResolvedValue(undefined);
    fetchContact.mockResolvedValue({
      id: 'contact-1',
      displayName: '8613428277520',
      remark: '',
      channelTypes: ['chatapp'],
      lastMessageAt: null,
      lastText: '',
      messageCount: 1,
      unreadCount: 0,
      identities: [{
        id: 'identity-1',
        channelType: 'chatapp',
        identityScope: 'd0a7f664-89ee-4658-b7c7-7c05e9a33552',
        identityValue: '8613428277520',
        displayName: '悦为小森',
      }],
    });
  });

  it('shows the ChatApp number without exposing the internal account UUID', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <ConfigProvider>
          <AntApp>
            <MemoryRouter initialEntries={['/thread/contact-1']}>
              <Routes>
                <Route path="/thread/:contactId" element={<ContactDetailPanel />} />
              </Routes>
            </MemoryRouter>
          </AntApp>
        </ConfigProvider>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('悦为小森')).toBeInTheDocument();
    expect(screen.getAllByText('8613428277520').length).toBeGreaterThan(0);
    expect(screen.queryByText(/d0a7f664-89ee-4658-b7c7-7c05e9a33552/)).not.toBeInTheDocument();
  });

  it('switches the shared thread channel when a non-WeCom identity is selected', async () => {
    fetchContact.mockResolvedValueOnce({
      id: 'contact-1',
      displayName: '客户一',
      remark: '',
      channelTypes: ['wecom', 'email'],
      lastMessageAt: null,
      lastText: '',
      messageCount: 2,
      unreadCount: 0,
      identities: [
        {
          id: 'wecom-identity',
          channelType: 'wecom',
          identityScope: 'external',
          identityValue: 'external-user',
          displayName: '企业微信客户',
        },
        {
          id: 'email-identity',
          channelType: 'email',
          identityScope: 'email',
          identityValue: 'customer@example.com',
          displayName: '客户邮箱',
        },
      ],
    });
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <ConfigProvider>
          <AntApp>
            <MemoryRouter initialEntries={['/thread/contact-1']}>
              <Routes>
                <Route path="/thread/:contactId" element={<ContactDetailPanel />} />
              </Routes>
            </MemoryRouter>
          </AntApp>
        </ConfigProvider>
      </QueryClientProvider>,
    );

    fireEvent.click(await screen.findByText('客户邮箱'));
    expect(selectChannel).toHaveBeenCalledWith('email');
  });

  it('saves edited CRM tags for the contact', async () => {
    fetchContact.mockResolvedValueOnce({
      id: 'contact-1',
      displayName: '客户一',
      remark: '重点客户',
      channelTypes: ['email'],
      lastMessageAt: null,
      lastText: '',
      messageCount: 0,
      unreadCount: 0,
      tags: [{ id: 'tag-1', name: 'VIP', color: 'gold' }],
      identities: [],
    });
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <ConfigProvider>
          <AntApp>
            <MemoryRouter initialEntries={['/thread/contact-1']}>
              <Routes>
                <Route path="/thread/:contactId" element={<ContactDetailPanel />} />
              </Routes>
            </MemoryRouter>
          </AntApp>
        </ConfigProvider>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('VIP')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '编辑标签' }));
    const user = userEvent.setup();
    const tagInput = screen.getByRole('combobox');
    await user.type(tagInput, '重点');
    await user.keyboard('{Enter}');
    await user.click(screen.getByRole('button', { name: '保存标签' }));

    expect(updateTagsMutateAsync).toHaveBeenCalledWith({
      id: 'contact-1',
      tags: [{ name: 'VIP' }, { name: '重点' }],
    });
  });
});
