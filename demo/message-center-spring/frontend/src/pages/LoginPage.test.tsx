import '@testing-library/jest-dom/vitest';
import { App as AntApp, ConfigProvider } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { AuthProvider } from '../hooks/useAuth';
import LoginPage from './LoginPage';

const api = vi.hoisted(() => ({
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  exchangeWeComLogin: vi.fn(),
  fetchAccountProfile: vi.fn(),
}));

vi.mock('../api/endpoints', () => api);
vi.mock('../components/wecom/WeComLoginPanel', () => ({
  WeComLoginPanel: ({ onAuthenticated }: {
    onAuthenticated: (code: string, state: string) => Promise<void>;
  }) => (
    <button type="button" aria-label="企业微信官方登录组件"
      onClick={() => void onAuthenticated('wecom-code', 'wecom-state')}>
      企业微信扫码
    </button>
  ),
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <ConfigProvider>
        <AntApp>
          <AuthProvider>
            <LoginPage />
          </AuthProvider>
        </AntApp>
      </ConfigProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  window.localStorage.clear();
  api.exchangeWeComLogin.mockResolvedValue({
    token: 'crm-token',
    username: 'employee-1',
    roles: ['USER'],
    viewerAuthToken: 'viewer-memory-only',
    viewerExpiresIn: 300,
  });
  api.fetchAccountProfile.mockResolvedValue({
    id: 'user-1', username: 'employee-1', displayName: '员工一', roles: ['USER'],
    avatar: { source: 'INITIAL', contentUrl: null, initial: '员', revision: null },
  });
  api.register.mockResolvedValue({ token: 'register-token', username: 'agent_01', roles: ['AGENT'] });
});

it('registers a custom login id and starts the new session', async () => {
  const user = userEvent.setup();
  renderPage();

  await user.click(screen.getByText('注册').closest('label')!);
  await user.type(screen.getByPlaceholderText('登录 ID'), 'agent_01');
  await user.type(screen.getByPlaceholderText('昵称'), '张三');
  await user.type(screen.getByPlaceholderText('密码'), 'Example123');
  await user.type(screen.getByPlaceholderText('确认密码'), 'Example123');
  await user.click(screen.getByRole('button', { name: '注册并登录' }));

  await waitFor(() => expect(api.register).toHaveBeenCalledWith({
    username: 'agent_01', displayName: '张三', password: 'Example123',
  }));
  expect(window.localStorage.getItem('token')).toBe('register-token');
});

it('keeps viewport padding inside the mobile login page height', () => {
  renderPage();
  const card = screen.getByRole('heading', { name: '统一消息中心' }).closest('.ant-card');

  expect(card?.parentElement).toHaveStyle({ boxSizing: 'border-box' });
});

it('keeps password login and exposes the official WeCom option', async () => {
  const user = userEvent.setup();
  renderPage();

  expect(screen.getByRole('tab', { name: '账号密码' })).toBeVisible();
  expect(screen.getByPlaceholderText('用户名')).toBeVisible();
  await user.click(screen.getByRole('tab', { name: '企业微信登录' }));
  expect(await screen.findByLabelText('企业微信官方登录组件')).toBeVisible();
});

it('stores only the CRM session after WeCom login', async () => {
  const user = userEvent.setup();
  renderPage();

  await user.click(screen.getByRole('tab', { name: '企业微信登录' }));
  await user.click(await screen.findByLabelText('企业微信官方登录组件'));

  await waitFor(() => expect(api.exchangeWeComLogin)
    .toHaveBeenCalledWith({ code: 'wecom-code', state: 'wecom-state' }));
  expect(window.localStorage.getItem('token')).toBe('crm-token');
  expect(window.localStorage.getItem('username')).toBe('employee-1');
  expect(window.localStorage.getItem('wecomViewerAuthToken')).toBeNull();
});
