import '@testing-library/jest-dom/vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { WeComBindingPanel } from './WeComBindingPanel';

const api = vi.hoisted(() => ({
  fetchWeComBinding: vi.fn(),
  exchangeWeComBinding: vi.fn(),
  unbindWeCom: vi.fn(),
}));
const auth = vi.hoisted(() => ({
  profile: {
    id: 'user-1', username: 'agent', displayName: '张三', roles: ['AGENT'],
    avatar: { source: 'INITIAL', contentUrl: null, initial: '张', revision: null },
  },
  refreshProfile: vi.fn(),
}));

vi.mock('../../api/endpoints', () => api);
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => auth }));
vi.mock('./WeComLoginPanel', () => ({
  WeComLoginPanel: ({ onAuthenticated }: {
    onAuthenticated: (code: string, state: string) => Promise<void>;
  }) => (
    <button
      aria-label="企业微信官方登录组件"
      onClick={() => void onAuthenticated('binding-code', 'binding-state')}
    >
      企业微信官方登录组件
    </button>
  ),
}));
vi.mock('./WeComAvatarAuthorizationModal', () => ({
  WeComAvatarAuthorizationModal: ({ open, onSucceeded }: {
    open: boolean;
    onSucceeded: () => Promise<void>;
  }) => open ? (
    <button aria-label="模拟头像授权成功" onClick={() => void onSucceeded()}>
      模拟头像授权成功
    </button>
  ) : null,
}));

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <WeComBindingPanel />
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  auth.profile.avatar.source = 'INITIAL';
  api.exchangeWeComBinding.mockResolvedValue({
    bound: true,
    userId: 'user-1',
    authCorpId: 'corp-1',
    wecomUserId: 'employee-1',
    provisioningSource: 'BOUND_EXISTING',
  });
  api.unbindWeCom.mockResolvedValue(undefined);
  auth.refreshProfile.mockResolvedValue(undefined);
});

it('offers private avatar authorization only when the bound account lacks a WeCom avatar', async () => {
  api.fetchWeComBinding.mockResolvedValue({
    bound: true,
    userId: 'user-1',
    authCorpId: 'corp-1',
    wecomUserId: 'employee-1',
    provisioningSource: 'BOUND_EXISTING',
  });
  const user = userEvent.setup();

  renderPanel();
  await user.click((await screen.findByText('授权企业微信头像')).closest('button')!);
  expect(await screen.findByLabelText('模拟头像授权成功')).toBeVisible();

  auth.profile.avatar.source = 'WECOM';
  renderPanel();
  expect(screen.queryAllByRole('button', { name: '授权企业微信头像' })).toHaveLength(0);
});

it('refreshes binding and account profile only after avatar authorization succeeds', async () => {
  api.fetchWeComBinding.mockResolvedValue({
    bound: true,
    userId: 'user-1',
    authCorpId: 'corp-1',
    wecomUserId: 'employee-1',
    provisioningSource: 'BOUND_EXISTING',
  });
  const user = userEvent.setup();

  renderPanel();
  await user.click((await screen.findByText('授权企业微信头像')).closest('button')!);
  expect(auth.refreshProfile).not.toHaveBeenCalled();
  await user.click(await screen.findByLabelText('模拟头像授权成功'));

  expect(auth.refreshProfile).toHaveBeenCalledTimes(1);
  expect(api.fetchWeComBinding).toHaveBeenCalledTimes(2);
});

it('shows current binding without exposing credential fields', async () => {
  api.fetchWeComBinding.mockResolvedValue({
    bound: true,
    userId: 'user-1',
    authCorpId: 'corp-1',
    wecomUserId: 'employee-1',
    wecomDisplayName: '张三',
    corpName: '示例企业',
    provisioningSource: 'BOUND_EXISTING',
    });

  renderPanel();

  expect(await screen.findByText('已绑定企业微信')).toBeVisible();
  expect(screen.getByText('张三')).toBeVisible();
  expect(screen.getByText('示例企业')).toBeVisible();
  expect(screen.queryByText('employee-1')).not.toBeInTheDocument();
  expect(screen.queryByText('corp-1')).not.toBeInTheDocument();
  expect(screen.queryByLabelText(/suite secret/i)).not.toBeInTheDocument();
});

it('uses safe placeholders when the WeCom profile or enterprise name is unavailable', async () => {
  api.fetchWeComBinding.mockResolvedValue({
    bound: true,
    userId: 'user-1',
    authCorpId: 'corp-internal-id',
    wecomUserId: 'employee-internal-id',
    wecomDisplayName: null,
    corpName: null,
    provisioningSource: 'BOUND_EXISTING',
  });

  renderPanel();

  expect(await screen.findAllByText('未获取')).toHaveLength(2);
  expect(screen.queryByText('employee-internal-id')).not.toBeInTheDocument();
  expect(screen.queryByText('corp-internal-id')).not.toBeInTheDocument();
});

it('uses the official login panel when binding an existing account', async () => {
  api.fetchWeComBinding.mockResolvedValue({
    bound: false,
    userId: 'user-1',
    authCorpId: null,
    wecomUserId: null,
    provisioningSource: null,
  });
  const user = userEvent.setup();

  renderPanel();
  await user.click(await screen.findByRole('button', { name: '绑定企业微信' }));

  expect(await screen.findByLabelText('企业微信官方登录组件')).toBeVisible();
});

it('refreshes the binding state only after a successful exchange', async () => {
  api.fetchWeComBinding
    .mockResolvedValueOnce({
      bound: false,
      userId: 'user-1',
      authCorpId: null,
      wecomUserId: null,
      provisioningSource: null,
    })
    .mockResolvedValue({
      bound: true,
      userId: 'user-1',
      authCorpId: 'corp-1',
      wecomUserId: 'employee-1',
      provisioningSource: 'BOUND_EXISTING',
    });
  const user = userEvent.setup();

  renderPanel();
  await user.click(await screen.findByRole('button', { name: '绑定企业微信' }));
  await user.click(await screen.findByLabelText('企业微信官方登录组件'));

  expect(api.exchangeWeComBinding.mock.calls[0]?.[0]).toEqual({
    code: 'binding-code',
    state: 'binding-state',
  });
  expect(await screen.findByText('已绑定企业微信')).toBeVisible();
  expect(auth.refreshProfile).toHaveBeenCalledTimes(1);
});
