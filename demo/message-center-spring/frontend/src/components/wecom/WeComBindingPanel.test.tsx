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

vi.mock('../../api/endpoints', () => api);
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
  api.exchangeWeComBinding.mockResolvedValue({
    bound: true,
    userId: 'user-1',
    authCorpId: 'corp-1',
    wecomUserId: 'employee-1',
    provisioningSource: 'BOUND_EXISTING',
  });
  api.unbindWeCom.mockResolvedValue(undefined);
});

it('shows current binding without exposing credential fields', async () => {
  api.fetchWeComBinding.mockResolvedValue({
    bound: true,
    userId: 'user-1',
    authCorpId: 'corp-1',
    wecomUserId: 'employee-1',
    provisioningSource: 'BOUND_EXISTING',
  });

  renderPanel();

  expect(await screen.findByText('已绑定企业微信')).toBeVisible();
  expect(screen.getByText('employee-1')).toBeVisible();
  expect(screen.queryByLabelText(/suite secret/i)).not.toBeInTheDocument();
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
});
