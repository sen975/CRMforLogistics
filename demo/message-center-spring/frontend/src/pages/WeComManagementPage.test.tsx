import '@testing-library/jest-dom/vitest';
import { App as AntApp, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import WeComManagementPage from './WeComManagementPage';

const api = vi.hoisted(() => ({
  fetchWeComInstallations: vi.fn(),
}));

vi.mock('../api/endpoints', () => api);
vi.mock('../hooks/useAuth', () => ({
  useAuth: vi.fn(),
}));

import { useAuth } from '../hooks/useAuth';

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <AntApp>
          <WeComManagementPage />
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(useAuth).mockReturnValue({
    token: 'token', username: 'admin', roles: ['ADMIN'], wecomViewerAuthToken: null,
    login: vi.fn(), loginWithWeCom: vi.fn(), logout: vi.fn(),
    isAuthenticated: true, isAdmin: true, canBroadcast: true,
  });
  api.fetchWeComInstallations.mockResolvedValue([{
    authCorpId: 'corp-1', agentId: '100', authStatus: 'ACTIVE',
    authorizedAt: '2026-08-18T00:00:00Z',
  }]);
});

describe('WeComManagementPage', () => {
  it('shows the installation selector and all P0 workbench tabs', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: '企业微信管理' })).toBeInTheDocument();
    expect(await screen.findByText(/企业名称未同步 · ACTIVE/)).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '应用群聊' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '客户联系' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '客户群' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '通讯录' })).toBeInTheDocument();
  });

  it('does not render management data for a non-admin user', () => {
    vi.mocked(useAuth).mockReturnValue({
      token: 'token', username: 'agent', roles: ['AGENT'], wecomViewerAuthToken: null,
      login: vi.fn(), loginWithWeCom: vi.fn(), logout: vi.fn(),
      isAuthenticated: true, isAdmin: false, canBroadcast: false,
    });
    renderPage();
    expect(screen.getByText('无权访问企业微信管理')).toBeInTheDocument();
    expect(api.fetchWeComInstallations).not.toHaveBeenCalled();
  });
});
