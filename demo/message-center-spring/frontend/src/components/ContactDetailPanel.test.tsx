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
  fetchManualReviewPending: vi.fn().mockResolvedValue([]),
  keepPendingTopic: vi.fn(),
  previewTopicFusion: vi.fn(),
  applyTopicFusion: vi.fn(),
  fetchManualReviewSources: vi.fn(),
  previewManualReview: vi.fn(),
  applyManualReview: vi.fn(),
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

  it('organizes contact details into topic, contact, account, and message tabs', async () => {
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

    expect(await screen.findByRole('tab', { name: 'Topic 时间轴' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '联系人信息' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '账号渠道' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '消息详情' })).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByRole('tab', { name: '联系人信息' }));
    expect(screen.getAllByText('联系人信息').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('8613428277520')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: '账号渠道' }));
    expect(screen.getAllByText('账号渠道').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('悦为小森')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: '消息详情' }));
    expect(screen.getByText('请从消息或 Topic 来源中选择一条消息')).toBeInTheDocument();
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

    await userEvent.setup().click(await screen.findByRole('tab', { name: '账号渠道' }));
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

    const user = userEvent.setup();
    await user.click(await screen.findByRole('tab', { name: '账号渠道' }));
    expect(screen.queryByText(/external: external-user/)).not.toBeInTheDocument();
    expect(screen.getByText('email: customer@example.com')).toBeInTheDocument();
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

    const user = userEvent.setup();
    await user.click(await screen.findByRole('tab', { name: '联系人信息' }));
    expect(await screen.findByText('VIP')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '编辑标签' }));
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
