import { App as AntApp, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import BroadcastsPage from './BroadcastsPage';

const api = vi.hoisted(() => ({
  fetchChannelCapabilities: vi.fn(),
  fetchChatAppBroadcasts: vi.fn(),
  fetchChatAppBroadcastDetail: vi.fn(),
  fetchChatAppBroadcastFailures: vi.fn(),
  fetchChatAppBroadcastTemplates: vi.fn(),
  fetchContacts: vi.fn(),
  createChatAppBroadcast: vi.fn(),
  retryChatAppBroadcastFailures: vi.fn(),
  requestChatAppBroadcastReconciliation: vi.fn(),
}));
const sse = vi.hoisted(() => ({ useSse: vi.fn() }));

vi.mock('../api/endpoints', () => api);
vi.mock('../hooks/useSse', () => sse);

const broadcast = {
  id: 'broadcast-1',
  channelAccountId: 'account-1',
  name: '八月通知',
  templateCode: 'shipping_notice',
  templateName: 'Shipping Notice',
  languageCode: 'zh_CN',
  recipientCount: 2,
  successCount: 1,
  failedCount: 1,
  processingCount: 0,
  status: 'PARTIALLY_FAILED',
  providerGroupMessageId: 'group-1',
  providerRequestId: 'submit-request-1',
  providerCode: 'OK',
  lastReconciliationRequestId: 'request-1',
  lastReconciliationProviderCode: 'OK',
  errorCode: null,
  errorMessage: null,
  retriesBroadcastId: null,
  createdByUserId: 'user-1',
  submittedAt: '2026-08-14T07:00:00Z',
  reconciledAt: '2026-08-14T07:01:00Z',
  createdAt: '2026-08-14T07:00:00Z',
  updatedAt: '2026-08-14T07:01:00Z',
};

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <AntApp>
          <BroadcastsPage />
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

async function choose(label: string, option: string) {
  fireEvent.mouseDown(screen.getByRole('combobox', { name: label }));
  await userEvent.click(await screen.findByText(option, { selector: '.ant-select-item-option-content' }));
}

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchChannelCapabilities.mockResolvedValue([{
    channelType: 'chatapp', channelAccountId: 'account-1', displayName: 'WhatsApp 主账号', authStatus: 'active',
  }]);
  api.fetchChatAppBroadcasts.mockResolvedValue({ records: [broadcast], total: 1, page: 1, size: 20 });
  api.fetchChatAppBroadcastFailures.mockResolvedValue({
    records: [{
      id: 'recipient-2', contactId: 'contact-2', contactIdentityId: 'identity-2',
      recipientName: '李四', maskedNumber: '*******2222', templateParams: { order: 'SO-2' },
      messageId: 'message-2',
      providerMessageId: 'wamid-2', providerUniqueMessageId: 'unique-2',
      status: 'FAILED_RECIPIENT', failureReason: 'blocked', providerSentAt: null,
      lastReconciledAt: '2026-08-14T07:01:00Z',
    }], total: 1, page: 1, size: 20,
  });
  api.fetchChatAppBroadcastTemplates.mockResolvedValue([{
    templateCode: 'shipping_notice', templateName: 'Shipping Notice',
    displayName: '发货提醒（Shipping Notice）', languageCode: 'zh_CN',
    body: '订单 $(order) 已发货', placeholders: ['order'], category: 'UTILITY',
    components: [], variableDefinitions: { order: ['SO-1'] },
  }]);
  api.fetchContacts.mockResolvedValue({
    records: [{
      id: 'contact-1', displayName: '张三', remark: '', channelTypes: ['chatapp'],
      lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0,
      identities: [{
        id: 'identity-1', channelType: 'chatapp', identityScope: 'account-1',
        identityValue: '60111111111', displayName: '张三',
      }],
    }], total: 1, size: 100, current: 1, pages: 1,
  });
  api.createChatAppBroadcast.mockResolvedValue({ ...broadcast, id: 'broadcast-2', status: 'QUEUED' });
  api.retryChatAppBroadcastFailures.mockResolvedValue({ ...broadcast, id: 'broadcast-3', status: 'QUEUED' });
  api.fetchChatAppBroadcastDetail.mockResolvedValue({
    broadcast: {
      ...broadcast, status: 'STATUS_UNKNOWN', successCount: 0, failedCount: 0,
      processingCount: 2, lastReconciliationRequestId: 'request-2',
      lastReconciliationProviderCode: 'InvalidParameter',
      errorCode: 'CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE',
      errorMessage: 'ListChatappMessage failed',
    },
    recipients: [{
      id: 'recipient-1', contactId: 'contact-1', contactIdentityId: 'identity-1',
      recipientName: '张三', maskedNumber: '*******1111', templateParams: { order: 'SO-1' },
      messageId: 'message-1', providerMessageId: null, providerUniqueMessageId: null,
      status: 'PROCESSING', failureReason: null, providerSentAt: null, lastReconciledAt: null,
    }],
    reconciliation: {
      evidenceRows: 3, matchedRows: 2, unmatchedRows: 1, processingRecipients: 2,
      latestDiagnosticCode: 'CHATAPP_PROVIDER_SUCCESS_FLAG_CONFLICT',
    },
  });
  api.requestChatAppBroadcastReconciliation.mockResolvedValue({ ...broadcast, status: 'RECONCILING' });
});

describe('BroadcastsPage', () => {
  it('shows reconciliation diagnostics and requests reconciliation without resending', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: '查看群发 八月通知' }));
    const drawer = await screen.findByRole('dialog', { name: '群发详情' });
    expect(within(drawer).getByText('对账异常')).toBeInTheDocument();
    expect(within(drawer).getByText('request-2')).toBeInTheDocument();
    expect(within(drawer).getByText('CHATAPP_PROVIDER_SUCCESS_FLAG_CONFLICT')).toBeInTheDocument();
    expect(within(drawer).getByText('message-1')).toBeInTheDocument();
    await user.click(within(drawer).getByRole('button', { name: '重新对账' }));

    await waitFor(() => expect(api.requestChatAppBroadcastReconciliation)
      .toHaveBeenCalledWith('broadcast-1'));
    expect(api.retryChatAppBroadcastFailures).not.toHaveBeenCalled();
  });

  it('shows recipient processing separately from reconciliation diagnostics', async () => {
    api.fetchChatAppBroadcasts.mockResolvedValue({
      records: [{ ...broadcast, status: 'RECONCILING', successCount: 2, failedCount: 1, processingCount: 1 }],
      total: 1, page: 1, size: 20,
    });

    renderPage();

    expect(await screen.findByText('处理中')).toBeInTheDocument();
    expect(screen.queryByText('对账中')).not.toBeInTheDocument();
    expect(screen.getByText('2 成功 / 1 处理中 / 1 失败')).toBeInTheDocument();
  });

  it('shows whole-batch status, failure details and retries only failed recipients', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('部分失败')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '查看 1 条失败' }));

    const drawer = await screen.findByRole('dialog', { name: '失败明细' });
    expect(await within(drawer).findByText('blocked')).toBeInTheDocument();
    expect(within(drawer).getByText('wamid-2')).toBeInTheDocument();
    expect(within(drawer).getByText('发送失败')).toBeInTheDocument();
    expect(within(drawer).getByText('2026/8/14 15:01:00')).toBeInTheDocument();
    await user.click(within(drawer).getByRole('button', { name: '仅重发失败联系人' }));

    await waitFor(() => expect(api.retryChatAppBroadcastFailures).toHaveBeenCalledWith(
      'broadcast-1', expect.objectContaining({ name: '八月通知 - 失败重发' }),
    ));
  });

  it('invalidates an open detail drawer when broadcast SSE arrives', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: '查看群发 八月通知' }));
    await screen.findByRole('dialog', { name: '群发详情' });
    expect(api.fetchChatAppBroadcastDetail).toHaveBeenCalledTimes(1);

    const calls = sse.useSse.mock.calls;
    const latestCallback = calls[calls.length - 1]?.[0] as (() => void) | undefined;
    expect(latestCallback).toBeDefined();
    await act(async () => latestCallback?.());

    await waitFor(() => expect(api.fetchChatAppBroadcastDetail).toHaveBeenCalledTimes(2));
  });

  it('creates an account-scoped template broadcast from selected contact identities', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('八月通知');

    await user.click(screen.getByRole('button', { name: '新建群发' }));
    const drawer = await screen.findByRole('dialog', { name: '新建群发' });
    await user.type(within(drawer).getByRole('textbox', { name: '群发名称' }), '发货批次');
    await choose('模板', '发货提醒（Shipping Notice）');
    await choose('联系人', '张三');
    await user.type(within(drawer).getByRole('textbox', { name: 'order' }), 'SO-1');
    await user.click(within(drawer).getByRole('button', { name: '创建群发' }));

    await waitFor(() => expect(api.createChatAppBroadcast).toHaveBeenCalled());
    expect(api.createChatAppBroadcast.mock.calls[0][0]).toEqual(expect.objectContaining({
        channelAccountId: 'account-1',
        name: '发货批次',
        templateCode: 'shipping_notice',
        languageCode: 'zh_CN',
        recipients: [{ contactIdentityId: 'identity-1', templateParams: {} }],
        sharedTemplateParams: { order: 'SO-1' },
      }));
  });

  it('reuses the create idempotency key when the same draft is retried', async () => {
    const user = userEvent.setup();
    api.createChatAppBroadcast
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ ...broadcast, id: 'broadcast-2', status: 'QUEUED' });
    renderPage();
    await screen.findByText('八月通知');

    await user.click(screen.getByRole('button', { name: '新建群发' }));
    const drawer = await screen.findByRole('dialog', { name: '新建群发' });
    await user.type(within(drawer).getByRole('textbox', { name: '群发名称' }), '幂等批次');
    await choose('模板', '发货提醒（Shipping Notice）');
    await choose('联系人', '张三');
    await user.type(within(drawer).getByRole('textbox', { name: 'order' }), 'SO-1');
    const submit = within(drawer).getByRole('button', { name: '创建群发' });

    await user.click(submit);
    await waitFor(() => expect(api.createChatAppBroadcast).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(within(drawer).getByRole('button', { name: '创建群发' })).toBeEnabled());
    await user.click(within(drawer).getByRole('button', { name: '创建群发' }));
    await waitFor(() => expect(api.createChatAppBroadcast).toHaveBeenCalledTimes(2));

    expect(api.createChatAppBroadcast.mock.calls[1][0].clientRequestId)
      .toBe(api.createChatAppBroadcast.mock.calls[0][0].clientRequestId);
  });

  it('reuses the failure-retry idempotency key after an unknown response', async () => {
    const user = userEvent.setup();
    api.retryChatAppBroadcastFailures
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ ...broadcast, id: 'broadcast-3', status: 'QUEUED' });
    renderPage();

    await screen.findByText('八月通知');
    await user.click(screen.getByRole('button', { name: '查看 1 条失败' }));
    const drawer = await screen.findByRole('dialog', { name: '失败明细' });
    const retry = within(drawer).getByRole('button', { name: '仅重发失败联系人' });

    await user.click(retry);
    await waitFor(() => expect(api.retryChatAppBroadcastFailures).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(within(drawer).getByRole('button', { name: '仅重发失败联系人' })).toBeEnabled());
    await user.click(within(drawer).getByRole('button', { name: '仅重发失败联系人' }));
    await waitFor(() => expect(api.retryChatAppBroadcastFailures).toHaveBeenCalledTimes(2));

    expect(api.retryChatAppBroadcastFailures.mock.calls[1][1].clientRequestId)
      .toBe(api.retryChatAppBroadcastFailures.mock.calls[0][1].clientRequestId);
  });

  it('clears the account-scoped draft when switching ChatApp accounts', async () => {
    const user = userEvent.setup();
    api.fetchChannelCapabilities.mockResolvedValue([
      {
        channelType: 'chatapp', channelAccountId: 'account-1', displayName: 'WhatsApp 主账号', authStatus: 'active',
      },
      {
        channelType: 'chatapp', channelAccountId: 'account-2', displayName: 'WhatsApp 备用账号', authStatus: 'active',
      },
    ]);
    renderPage();
    await screen.findByText('八月通知');

    await user.click(screen.getByRole('button', { name: '新建群发' }));
    const drawer = await screen.findByRole('dialog', { name: '新建群发' });
    await user.type(within(drawer).getByRole('textbox', { name: '群发名称' }), '账号一草稿');
    await choose('模板', '发货提醒（Shipping Notice）');
    await choose('联系人', '张三');
    await user.type(within(drawer).getByRole('textbox', { name: 'order' }), 'SO-1');

    await choose('ChatApp 账号', 'WhatsApp 备用账号');

    expect(within(drawer).getByRole('textbox', { name: '群发名称' })).toHaveValue('');
    expect(within(drawer).getByRole('combobox', { name: '模板' })).not.toHaveAttribute('title', '发货提醒（Shipping Notice）');
    expect(within(drawer).getByRole('combobox', { name: '联系人' })).not.toHaveAttribute('title', '张三');
    expect(within(drawer).queryByRole('textbox', { name: 'order' })).not.toBeInTheDocument();
  });

  it('shows retry actions when account-scoped templates or contacts fail to load', async () => {
    const user = userEvent.setup();
    api.fetchChatAppBroadcastTemplates.mockRejectedValue(new Error('template network error'));
    api.fetchContacts.mockRejectedValue(new Error('contact network error'));
    renderPage();
    await screen.findByText('八月通知');

    await user.click(screen.getByRole('button', { name: '新建群发' }));
    const drawer = await screen.findByRole('dialog', { name: '新建群发' });
    const retryTemplates = await within(drawer).findByRole('button', { name: '重试模板' });
    const retryContacts = await within(drawer).findByRole('button', { name: '重试联系人' });

    await user.click(retryTemplates);
    await user.click(retryContacts);

    await waitFor(() => expect(api.fetchChatAppBroadcastTemplates).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(api.fetchContacts).toHaveBeenCalledTimes(2));
  });
});
