import { App as AntApp, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ContactResponse } from '../api/types';
import SendPage from './SendPage';

const api = vi.hoisted(() => ({
  fetchChannelCapabilities: vi.fn(),
  fetchContacts: vi.fn(),
  fetchTemplates: vi.fn(),
  sendForm: vi.fn(),
}));

vi.mock('../api/endpoints', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/endpoints')>()),
  ...api,
}));

vi.mock('../components/SendForm', () => ({
  default: (props: { selectedChannelAccountId?: string }) => {
    api.sendForm(props);
    return <div>发送表单</div>;
  },
}));

function page(records: ContactResponse[] = []) {
  return {
    records,
    total: records.length,
    size: 20,
    current: 1,
    pages: 1,
  };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider><AntApp><SendPage /></AntApp></ConfigProvider>
    </QueryClientProvider>,
  );
  return { ...view, queryClient };
}

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchTemplates.mockResolvedValue([]);
});

describe('SendPage ChatApp contacts', () => {
  it('does not request contacts when no active ChatApp account is available', async () => {
    api.fetchChannelCapabilities.mockResolvedValue([]);
    api.fetchContacts.mockResolvedValue(page());

    renderPage();

    expect(await screen.findByText('暂无可用 ChatApp 账号')).toBeInTheDocument();
    expect(api.fetchContacts).not.toHaveBeenCalled();
  });

  it('queries CAMS history contacts with the fixed active account and shows the required empty state', async () => {
    api.fetchChannelCapabilities.mockResolvedValue([{
      channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active',
    }]);
    api.fetchContacts.mockResolvedValue(page());

    renderPage();

    expect(await screen.findByText('暂无 CAMS 消息历史联系人')).toBeInTheDocument();
    await waitFor(() => expect(api.fetchContacts).toHaveBeenCalledWith(expect.objectContaining({
      channelType: 'chatapp', channelAccountId: 'account-a',
    })));
    expect(screen.queryByLabelText('ChatApp 账号')).not.toBeInTheDocument();
  });

  it('switches only between active account names and gives each account an isolated contact query', async () => {
    const user = userEvent.setup();
    api.fetchChannelCapabilities.mockResolvedValue([
      { channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active' },
      { channelType: 'chatapp', channelAccountId: 'account-b', displayName: 'CAMS 二号账号', authStatus: 'active' },
      { channelType: 'chatapp', channelAccountId: 'account-inactive', displayName: '停用账号', authStatus: 'inactive' },
    ]);
    api.fetchContacts.mockImplementation(async ({ channelAccountId }: { channelAccountId: string }) => page([{
      id: channelAccountId,
      displayName: channelAccountId === 'account-a' ? '账号一联系人' : '账号二联系人',
      remark: '', channelTypes: ['chatapp'], lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0, identities: [],
    }]));

    renderPage();

    const accountSelect = await screen.findByRole('combobox', { name: 'ChatApp 账号' });
    fireEvent.mouseDown(accountSelect);
    expect(await screen.findByRole('option', { name: 'CAMS 一号账号' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'CAMS 二号账号' })).toBeInTheDocument();
    expect(screen.getAllByRole('option')).toHaveLength(2);

    await user.click(screen.getByText('CAMS 二号账号', { selector: '.ant-select-item-option-content' }));
    await waitFor(() => expect(api.fetchContacts).toHaveBeenCalledWith(expect.objectContaining({
      channelType: 'chatapp', channelAccountId: 'account-b',
    })));
  });

  it('clears the selected contact when switching accounts and passes the current account to SendForm', async () => {
    const user = userEvent.setup();
    api.fetchChannelCapabilities.mockResolvedValue([
      { channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active' },
      { channelType: 'chatapp', channelAccountId: 'account-b', displayName: 'CAMS 二号账号', authStatus: 'active' },
    ]);
    api.fetchContacts.mockImplementation(async ({ channelAccountId }: { channelAccountId: string }) => page([{
      id: 'shared-contact',
      displayName: channelAccountId === 'account-a' ? '账号一联系人' : '账号二联系人',
      remark: '', channelTypes: ['chatapp'], lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0, identities: [],
    }]));

    renderPage();

    const contactSelect = (await screen.findAllByRole('combobox'))[1];
    fireEvent.mouseDown(contactSelect);
    await user.click(await screen.findByText('账号一联系人 (chatapp)', { exact: true }));
    expect(await screen.findByText('发送表单')).toBeInTheDocument();
    expect(api.sendForm).toHaveBeenCalledWith(expect.objectContaining({ selectedChannelAccountId: 'account-a' }));

    const accountSelect = screen.getByRole('combobox', { name: 'ChatApp 账号' });
    fireEvent.mouseDown(accountSelect);
    await user.click(screen.getByText('CAMS 二号账号', { selector: '.ant-select-item-option-content' }));
    await waitFor(() => expect(api.fetchContacts).toHaveBeenCalledWith(expect.objectContaining({
      channelAccountId: 'account-b',
    })));
    expect(screen.queryByText('发送表单')).not.toBeInTheDocument();
  });

  it('keeps the selected account stable when capability order changes', async () => {
    let capabilities = [
      { channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active' },
      { channelType: 'chatapp', channelAccountId: 'account-b', displayName: 'CAMS 二号账号', authStatus: 'active' },
    ];
    api.fetchChannelCapabilities.mockImplementation(() => Promise.resolve(capabilities));
    api.fetchContacts.mockImplementation(async ({ channelAccountId }: { channelAccountId: string }) => page([{
      id: 'shared-contact',
      displayName: channelAccountId === 'account-a' ? '账号一联系人' : '账号二联系人',
      remark: '', channelTypes: ['chatapp'], lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0, identities: [],
    }]));

    const { queryClient } = renderPage();

    const contactSelect = (await screen.findAllByRole('combobox'))[1];
    fireEvent.mouseDown(contactSelect);
    await userEvent.setup().click(await screen.findByText('账号一联系人 (chatapp)', { exact: true }));
    expect(await screen.findByText('发送表单')).toBeInTheDocument();

    capabilities = [...capabilities].reverse();
    await queryClient.refetchQueries({ queryKey: ['channelCapabilities'] });

    await waitFor(() => expect(api.sendForm).toHaveBeenLastCalledWith(expect.objectContaining({
      selectedChannelAccountId: 'account-a',
    })));
    expect(api.fetchContacts).not.toHaveBeenCalledWith(expect.objectContaining({ channelAccountId: 'account-b' }));
  });

  it('clears the selected shared contact when its active account becomes inactive', async () => {
    let capabilities = [
      { channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active' },
      { channelType: 'chatapp', channelAccountId: 'account-b', displayName: 'CAMS 二号账号', authStatus: 'active' },
    ];
    api.fetchChannelCapabilities.mockImplementation(() => Promise.resolve(capabilities));
    api.fetchContacts.mockImplementation(async ({ channelAccountId }: { channelAccountId: string }) => page([{
      id: 'shared-contact',
      displayName: channelAccountId === 'account-a' ? '账号一联系人' : '账号二联系人',
      remark: '', channelTypes: ['chatapp'], lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0, identities: [],
    }]));

    const { queryClient } = renderPage();
    const user = userEvent.setup();
    const accountSelect = await screen.findByRole('combobox', { name: 'ChatApp 账号' });
    fireEvent.mouseDown(accountSelect);
    await user.click(await screen.findByText('CAMS 二号账号', { selector: '.ant-select-item-option-content' }));
    const contactSelect = (await screen.findAllByRole('combobox'))[1];
    fireEvent.mouseDown(contactSelect);
    await user.click(await screen.findByText('账号二联系人 (chatapp)', { exact: true }));
    expect(await screen.findByText('发送表单')).toBeInTheDocument();

    capabilities = [capabilities[0], { ...capabilities[1], authStatus: 'inactive' }];
    await queryClient.refetchQueries({ queryKey: ['channelCapabilities'] });

    await waitFor(() => expect(api.fetchContacts).toHaveBeenLastCalledWith(expect.objectContaining({
      channelAccountId: 'account-a',
    })));
    expect(screen.queryByText('发送表单')).not.toBeInTheDocument();
  });

  it('clears the selected shared contact when its active account is removed', async () => {
    let capabilities = [
      { channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active' },
      { channelType: 'chatapp', channelAccountId: 'account-b', displayName: 'CAMS 二号账号', authStatus: 'active' },
    ];
    api.fetchChannelCapabilities.mockImplementation(() => Promise.resolve(capabilities));
    api.fetchContacts.mockImplementation(async ({ channelAccountId }: { channelAccountId: string }) => page([{
      id: 'shared-contact',
      displayName: channelAccountId === 'account-a' ? '账号一联系人' : '账号二联系人',
      remark: '', channelTypes: ['chatapp'], lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0, identities: [],
    }]));

    const { queryClient } = renderPage();
    const user = userEvent.setup();
    const accountSelect = await screen.findByRole('combobox', { name: 'ChatApp 账号' });
    fireEvent.mouseDown(accountSelect);
    await user.click(await screen.findByText('CAMS 二号账号', { selector: '.ant-select-item-option-content' }));
    const contactSelect = (await screen.findAllByRole('combobox'))[1];
    fireEvent.mouseDown(contactSelect);
    await user.click(await screen.findByText('账号二联系人 (chatapp)', { exact: true }));
    expect(await screen.findByText('发送表单')).toBeInTheDocument();

    capabilities = [capabilities[0]];
    await queryClient.refetchQueries({ queryKey: ['channelCapabilities'] });

    await waitFor(() => expect(api.fetchContacts).toHaveBeenLastCalledWith(expect.objectContaining({
      channelAccountId: 'account-a',
    })));
    expect(screen.queryByText('发送表单')).not.toBeInTheDocument();
  });

  it('sends the switched search mode to the contact query', async () => {
    const user = userEvent.setup();
    api.fetchChannelCapabilities.mockResolvedValue([{
      channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active',
    }]);
    api.fetchContacts.mockResolvedValue(page([{
      id: 'contact-1', displayName: '张三', remark: '', channelTypes: ['chatapp'],
      lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0, identities: [],
    }]));

    renderPage();

    // antd 把 Select 的 placeholder 渲染成 span，而不是 input 的 placeholder 属性
    expect(await screen.findByText('搜索并选择联系人')).toBeInTheDocument();
    await user.type(screen.getByRole('combobox'), '张三');

    await user.click(screen.getByRole('button', { name: '搜索模式' }));
    await user.click(await screen.findByRole('menuitem', { name: '标签' }));

    expect(await screen.findByText('搜索标签名')).toBeInTheDocument();
    // 只断言「新的 searchMode 真的走到了 API」。这里刻意不断言关键词被清空：
    // 点「搜索模式」按钮一定会让收件人下拉失焦关闭，而 rc-select 关闭下拉时自己就会
    // 回调 onSearch('') 清空搜索词，所以 changeSearchMode 里的 setSearch('') 在这条
    // 交互路径上不可观测（删掉它本用例仍然通过，已实测）。关键词/选中项的重置由
    // 「切模式清空已选收件人」用例钉住。
    await waitFor(() => expect(api.fetchContacts).toHaveBeenLastCalledWith(expect.objectContaining({
      searchMode: 'tag', channelAccountId: 'account-a',
    })));
  });

  it('clears the selected recipient when the search mode changes', async () => {
    const user = userEvent.setup();
    api.fetchChannelCapabilities.mockResolvedValue([{
      channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active',
    }]);
    api.fetchContacts.mockResolvedValue(page([{
      id: 'contact-1', displayName: '张三', remark: '', channelTypes: ['chatapp'],
      lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0, identities: [],
    }]));

    renderPage();

    const contactSelect = await screen.findByRole('combobox');
    fireEvent.mouseDown(contactSelect);
    await user.click(await screen.findByText('张三 (chatapp)', { exact: true }));
    expect(await screen.findByText('发送表单')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '搜索模式' }));
    await user.click(await screen.findByRole('menuitem', { name: '标签' }));

    // 切模式后不允许残留旧收件人：下拉框不再显示该选中项，下方回到「先选择一个联系人」。
    await waitFor(() => expect(screen.queryByText('发送表单')).not.toBeInTheDocument());
    expect(await screen.findByText('先选择一个联系人')).toBeInTheDocument();
  });
});
