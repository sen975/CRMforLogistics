import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp, ConfigProvider } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ContactDetailPanel from './ContactDetailPanel';

const fetchContact = vi.fn();
const fetchContactMemory = vi.fn();
const selectChannel = vi.fn();
const updateTagsMutateAsync = vi.fn();

vi.mock('../api/endpoints', () => ({
  fetchContact: (...args: unknown[]) => fetchContact(...args),
  fetchContactMemory: (...args: unknown[]) => fetchContactMemory(...args),
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
    fetchContactMemory.mockReset();
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

  it('shows profile and separates human tags from read-only ai tags', async () => {
    fetchContact.mockResolvedValueOnce({
      id: 'contact-1',
      displayName: '客户一',
      remark: '',
      channelTypes: ['email'],
      lastMessageAt: null,
      lastText: '',
      messageCount: 2,
      unreadCount: 0,
      tags: [{ id: 'manual-1', name: '人工VIP', color: 'gold' }],
      identities: [],
      memory: {
        profile: {
          id: 'profile-1',
          version: 2,
          content: '客户关注海运时效，倾向通过邮件确认方案。',
          createdAt: '2026-09-11T00:00:00Z',
        },
        humanTags: [{ id: 'manual-1', name: '人工VIP', color: 'gold' }],
        aiTags: [{
          id: 'ai-1',
          name: '海运客户',
          category: 'PRODUCT_INTEREST',
          colorToken: 'green',
          status: 'STALE',
          confidence: 0.91,
        }],
        state: 'PROCESSING',
        lastSuccessAt: '2026-09-10T00:00:00Z',
        lastFailureCode: null,
        pendingInbound: true,
        aiTagsNextCursor: null,
        aiTagsHasMore: false,
      },
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
    expect(await screen.findByText('AI 画像')).toBeInTheDocument();
    expect(screen.getByText('客户关注海运时效，倾向通过邮件确认方案。')).toBeInTheDocument();
    expect(screen.getByText('AI 标签')).toBeInTheDocument();
    expect(screen.getByText('海运客户')).toBeInTheDocument();
    expect(screen.getByText('人工标签')).toBeInTheDocument();
    expect(screen.getByText('人工VIP')).toBeInTheDocument();
    expect(screen.getByText('正在更新')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '编辑AI标签' })).not.toBeInTheDocument();
  });

  it('loads the next page of ai tags without changing human tags', async () => {
    fetchContact.mockResolvedValueOnce({
      id: 'contact-1',
      displayName: '客户一',
      remark: '',
      channelTypes: ['email'],
      lastMessageAt: null,
      lastText: '',
      messageCount: 2,
      unreadCount: 0,
      tags: [{ id: 'manual-1', name: '人工VIP', color: 'gold' }],
      identities: [],
      memory: {
        profile: null,
        humanTags: [{ id: 'manual-1', name: '人工VIP', color: 'gold' }],
        aiTags: [{ id: 'ai-1', name: '海运客户', category: 'PRODUCT_INTEREST', colorToken: 'green', status: 'ACTIVE', confidence: 0.9 }],
        state: 'CLEAN',
        lastSuccessAt: null,
        lastFailureCode: null,
        pendingInbound: false,
        aiTagsNextCursor: 'cursor-1',
        aiTagsHasMore: true,
      },
    });
    fetchContactMemory.mockResolvedValueOnce({
      profile: null,
      humanTags: [{ id: 'manual-1', name: '人工VIP', color: 'gold' }],
      aiTags: [{ id: 'ai-2', name: '时效敏感', category: 'NEED', colorToken: 'blue', status: 'STALE', confidence: 0.8 }],
      state: 'CLEAN',
      lastSuccessAt: null,
      lastFailureCode: null,
      pendingInbound: false,
      aiTagsNextCursor: null,
      aiTagsHasMore: false,
    });

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <ConfigProvider><AntApp><MemoryRouter initialEntries={['/thread/contact-1']}><Routes>
          <Route path="/thread/:contactId" element={<ContactDetailPanel />} />
        </Routes></MemoryRouter></AntApp></ConfigProvider>
      </QueryClientProvider>,
    );

    const user = userEvent.setup();
    await user.click(await screen.findByRole('tab', { name: '联系人信息' }));
    await user.click(screen.getByRole('button', { name: '加载更多' }));
    expect(await screen.findByText('时效敏感')).toBeInTheDocument();
    expect(screen.getByText('人工VIP')).toBeInTheDocument();
    expect(fetchContactMemory).toHaveBeenCalledWith('contact-1', { limit: 100, cursor: 'cursor-1' });
  });

  it('shows the channel name and the CRM remark as two separate fields', async () => {
    fetchContact.mockResolvedValueOnce({
      id: 'contact-1',
      displayName: 'calm1026',
      remark: '张百凡',
      channelTypes: ['wecom'],
      lastMessageAt: null,
      lastText: '',
      messageCount: 0,
      unreadCount: 0,
      tags: [],
      identities: [],
    });

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <ConfigProvider><AntApp><MemoryRouter initialEntries={['/thread/contact-1']}><Routes>
          <Route path="/thread/:contactId" element={<ContactDetailPanel />} />
        </Routes></MemoryRouter></AntApp></ConfigProvider>
      </QueryClientProvider>,
    );

    const user = userEvent.setup();
    await user.click(await screen.findByRole('tab', { name: '联系人信息' }));

    const nameRow = (await screen.findByText('名称')).closest('tr');
    const remarkRow = screen.getByText('备注').closest('tr');
    // 「名称」是渠道真名，备注不顶替它（列表里用备注认人；详情里两个都要给）
    expect(nameRow?.textContent).toContain('calm1026');
    expect(nameRow?.textContent).not.toContain('张百凡');
    expect(remarkRow?.textContent).toContain('张百凡');
    // 旧实现两行显示同一段文字——这正是「名称被备注覆盖」的症状
    expect(nameRow?.textContent).not.toEqual(remarkRow?.textContent);
  });

  it('keeps the name cell empty instead of borrowing the remark when the channel has no name', async () => {
    fetchContact.mockResolvedValueOnce({
      id: 'contact-1',
      displayName: '',
      remark: '张百凡',
      channelTypes: [],
      lastMessageAt: null,
      lastText: '',
      messageCount: 0,
      unreadCount: 0,
      tags: [],
      identities: [],
    });

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <ConfigProvider><AntApp><MemoryRouter initialEntries={['/thread/contact-1']}><Routes>
          <Route path="/thread/:contactId" element={<ContactDetailPanel />} />
        </Routes></MemoryRouter></AntApp></ConfigProvider>
      </QueryClientProvider>,
    );

    const user = userEvent.setup();
    await user.click(await screen.findByRole('tab', { name: '联系人信息' }));

    const nameRow = (await screen.findByText('名称')).closest('tr');
    const remarkRow = screen.getByText('备注').closest('tr');
    expect(nameRow?.textContent).toContain('-');
    expect(nameRow?.textContent).not.toContain('张百凡');
    expect(remarkRow?.textContent).toContain('张百凡');
  });
});
