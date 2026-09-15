import '@testing-library/jest-dom/vitest';
import { App as AntApp, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import ChannelSettingsPage from './ChannelSettingsPage';

const api = vi.hoisted(() => ({
  fetchChannelAccounts: vi.fn(),
  createChannelAccount: vi.fn(),
  updateChannelAccount: vi.fn(),
  triggerChannelSync: vi.fn(),
  fetchChannelCredentials: vi.fn(),
  updateChannelCredentials: vi.fn(),
  unbindChannelAccount: vi.fn(),
  fetchWeComBinding: vi.fn(),
  fetchWhatsAppCapability: vi.fn(),
}));

const auth = vi.hoisted(() => ({ isAdmin: true }));

vi.mock('../api/endpoints', () => api);
vi.mock('../components/wecom/WeComBindingPanel', () => ({
  WeComBindingPanel: () => <div>企业微信官方授权二维码</div>,
}));
vi.mock('../components/whatsapp/AdminWhatsAppAccountPanel', () => ({
  AdminWhatsAppAccountPanel: ({ isAdmin }: { isAdmin: boolean }) => (
    <div>{isAdmin ? '管理员 WhatsApp 账号管理' : '我的 WhatsApp 发送账号'}</div>
  ),
}));
vi.mock('../hooks/useAuth', () => ({
  useAuth: () => ({ isAdmin: auth.isAdmin }),
}));

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ConfigProvider>
          <AntApp>
            <ChannelSettingsPage />
          </AntApp>
        </ConfigProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchChannelAccounts.mockResolvedValue([]);
  api.fetchWeComBinding.mockResolvedValue({
    bound: false,
    userId: 'user-1',
    authCorpId: null,
    wecomUserId: null,
    provisioningSource: null,
  });
  api.fetchWhatsAppCapability.mockResolvedValue({ ready: true });
});

it('管理员看到 WhatsApp 账号管理入口而非自助绑定入口', async () => {
  auth.isAdmin = true;
  renderPage();

  expect(await screen.findByText('管理员 WhatsApp 账号管理')).toBeVisible();
  expect(screen.queryByText('绑定 Business App 共存')).not.toBeInTheDocument();
  expect(screen.queryByText('绑定企业 API 电话')).not.toBeInTheDocument();
});

it('销售只看到自己的 WhatsApp 发送账号摘要入口', async () => {
  auth.isAdmin = false;
  renderPage();

  expect(await screen.findByText('我的 WhatsApp 发送账号')).toBeVisible();
  expect(screen.queryByText('管理员 WhatsApp 账号管理')).not.toBeInTheDocument();
  expect(screen.queryByText('绑定 Business App 共存')).not.toBeInTheDocument();
  expect(screen.queryByText('绑定企业 API 电话')).not.toBeInTheDocument();
});

it('仍保留邮件和企业微信配置入口', async () => {
  auth.isAdmin = false;
  renderPage();

  expect(await screen.findByText('邮件')).toBeVisible();
  expect(screen.getByText('企业微信')).toBeVisible();
  const buttonsByText = (text: string) => screen.getAllByRole('button').filter(
    (button) => button.textContent?.replace(/\s/g, '') === text,
  );
  expect(buttonsByText('配置')).toHaveLength(1);
  expect(buttonsByText('授权')).toHaveLength(1);
});
