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

vi.mock('../api/endpoints', () => api);
vi.mock('../components/wecom/WeComBindingPanel', () => ({
  WeComBindingPanel: () => <div>企业微信官方授权二维码</div>,
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

it('renders WhatsApp, email, and WeCom configuration slots when no accounts exist', async () => {
  renderPage();

  expect(await screen.findByText('WhatsApp')).toBeVisible();
  expect(screen.getByText('邮件')).toBeVisible();
  expect(screen.getByText('企业微信')).toBeVisible();
  const buttonsByText = (text: string) => screen.getAllByRole('button').filter(
    (button) => button.textContent?.replace(/\s/g, '') === text,
  );
  expect(buttonsByText('配置')).toHaveLength(1);
  expect(buttonsByText('授权')).toHaveLength(1);
  expect(buttonsByText('绑定BusinessApp共存')).toHaveLength(1);
  expect(buttonsByText('绑定BusinessAPI')).toHaveLength(1);
});
