import '@testing-library/jest-dom/vitest';
import { App as AntApp, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { AdminWhatsAppCallbackPanel } from './AdminWhatsAppCallbackPanel';

const api = vi.hoisted(() => ({
  fetchAdminWhatsAppCallbacks: vi.fn(),
  updateAdminWhatsAppPhoneCallback: vi.fn(),
  updateAdminWhatsAppAccountCallback: vi.fn(),
}));

vi.mock('../../api/endpoints', () => api);

const callbackConfig = {
  scopeId: 'scope-1',
  ingressPath: '/api/v1/webhooks/chatapp',
  accountConfig: {
    desiredStatusCallbackUrl: 'https://hooks.example.com/account/status',
    httpFlag: 'Y',
    queueFlag: 'N',
    providerState: 'UNKNOWN',
    lastApplyStatus: 'SUCCEEDED',
    lastErrorCode: null,
    lastAppliedAt: '2026-09-20T08:00:00Z',
    version: 3,
  },
  phoneConfigs: [{
    channelAccountId: 'account-1',
    maskedPhone: '********9485',
    desiredUpCallbackUrl: 'https://hooks.example.com/messages',
    desiredStatusCallbackUrl: 'https://hooks.example.com/status',
    httpFlag: 'Y',
    queueFlag: 'N',
    providerState: 'UNKNOWN',
    lastApplyStatus: 'SUCCEEDED',
    lastErrorCode: null,
    lastAppliedAt: '2026-09-20T08:00:00Z',
    version: 7,
  }],
};

function renderPanel(isAdmin = true) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <AntApp>
          <AdminWhatsAppCallbackPanel scopeId="scope-1" isAdmin={isAdmin} />
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchAdminWhatsAppCallbacks.mockResolvedValue(callbackConfig);
  api.updateAdminWhatsAppPhoneCallback.mockResolvedValue(callbackConfig);
  api.updateAdminWhatsAppAccountCallback.mockResolvedValue(callbackConfig);
});

it('只向管理员展示账户级和号码级回调配置', async () => {
  const { rerender } = renderPanel(true);

  expect(await screen.findByRole('region', { name: 'CAMS 回调地址' })).toBeVisible();
  expect(await screen.findByDisplayValue('https://hooks.example.com/account/status')).toBeVisible();
  expect(screen.getByDisplayValue('https://hooks.example.com/messages')).toBeVisible();
  expect(screen.getByDisplayValue('https://hooks.example.com/status')).toBeVisible();
  expect(screen.queryByLabelText('账户级上行消息地址')).not.toBeInTheDocument();

  rerender(
    <QueryClientProvider client={new QueryClient()}>
      <ConfigProvider><AntApp><AdminWhatsAppCallbackPanel scopeId="scope-1" isAdmin={false} /></AntApp></ConfigProvider>
    </QueryClientProvider>,
  );
  expect(screen.queryByRole('region', { name: 'CAMS 回调地址' })).not.toBeInTheDocument();
});

it('保存号码级配置只提交 URL、flag 和版本', async () => {
  const user = userEvent.setup();
  renderPanel();

  const row = (await screen.findByText('********9485')).closest('tr');
  if (!row) throw new Error('phone callback row missing');
  const upstream = within(row).getByLabelText('上行消息地址');
  await user.clear(upstream);
  await user.type(upstream, 'https://new.example.com/inbound');
  await user.click(within(row).getByRole('button', { name: '保存号码回调' }));

  await waitFor(() => expect(api.updateAdminWhatsAppPhoneCallback).toHaveBeenCalledWith(
    'scope-1',
    'account-1',
    {
      upCallbackUrl: 'https://new.example.com/inbound',
      statusCallbackUrl: 'https://hooks.example.com/status',
      httpFlag: 'Y',
      queueFlag: 'N',
      expectedVersion: 7,
    },
  ));
});

it('版本冲突时重新获取服务端状态', async () => {
  const user = userEvent.setup();
  api.updateAdminWhatsAppAccountCallback.mockRejectedValue({ response: { status: 409 } });
  renderPanel();

  await screen.findByDisplayValue('https://hooks.example.com/account/status');
  await user.click(screen.getByRole('button', { name: '保存账户回调' }));

  await waitFor(() => expect(api.fetchAdminWhatsAppCallbacks).toHaveBeenCalledTimes(2));
  expect(await screen.findByText('配置版本已变化，已重新加载')).toBeVisible();
});

it('最近写入失败时展示失败状态且不宣称已生效', async () => {
  api.fetchAdminWhatsAppCallbacks.mockResolvedValue({
    ...callbackConfig,
    phoneConfigs: [{
      ...callbackConfig.phoneConfigs[0],
      lastApplyStatus: 'FAILED',
      lastErrorCode: 'WHATSAPP_CALLBACK_PROVIDER_FAILED',
    }],
  });
  renderPanel();

  const row = (await screen.findByText('********9485')).closest('tr');
  if (!row) throw new Error('phone callback row missing');
  expect(within(row).getByText('最近写入失败')).toBeVisible();
  expect(within(row).queryByText('已生效')).not.toBeInTheDocument();
});
