import '@testing-library/jest-dom/vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { WeComAvatarAuthorizationModal } from './WeComAvatarAuthorizationModal';

const api = vi.hoisted(() => ({
  createWeComAvatarAuthorization: vi.fn(),
  fetchWeComAvatarAuthorization: vi.fn(),
}));

vi.mock('../../api/endpoints', () => api);

function renderModal(props?: Partial<React.ComponentProps<typeof WeComAvatarAuthorizationModal>>) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <WeComAvatarAuthorizationModal
        open
        onClose={vi.fn()}
        onSucceeded={vi.fn()}
        {...props}
      />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  Object.defineProperty(window.navigator, 'userAgent', {
    configurable: true,
    value: 'Mozilla/5.0 Chrome/124',
  });
  api.createWeComAvatarAuthorization.mockResolvedValue({
    authorizationId: 'authorization-1',
    authorizationUrl: 'https://open.weixin.qq.com/connect/oauth2/authorize?state=secret',
    status: 'PENDING',
    expiresIn: 300,
  });
  api.fetchWeComAvatarAuthorization.mockResolvedValue({
    authorizationId: 'authorization-1',
    status: 'PENDING',
    errorCode: null,
  });
});

afterEach(() => {
  vi.useRealTimers();
});

it('creates an authorization and renders its server URL as a desktop QR code', async () => {
  renderModal();

  expect(await screen.findByLabelText('企业微信头像授权二维码')).toBeInTheDocument();
  expect(api.createWeComAvatarAuthorization).toHaveBeenCalledTimes(1);
});

it('uses a direct authorization action inside the WeCom browser', async () => {
  Object.defineProperty(window.navigator, 'userAgent', {
    configurable: true,
    value: 'Mozilla/5.0 wxwork/4.1.30',
  });

  renderModal();

  const link = await screen.findByRole('link', { name: '在企业微信中授权' });
  expect(link).toHaveAttribute(
    'href',
    'https://open.weixin.qq.com/connect/oauth2/authorize?state=secret',
  );
  expect(screen.queryByLabelText('企业微信头像授权二维码')).not.toBeInTheDocument();
});

it('polls while pending and refreshes only after the succeeded terminal state', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const onSucceeded = vi.fn().mockResolvedValue(undefined);
  api.fetchWeComAvatarAuthorization
    .mockResolvedValueOnce({ authorizationId: 'authorization-1', status: 'PENDING', errorCode: null })
    .mockResolvedValueOnce({ authorizationId: 'authorization-1', status: 'SUCCEEDED', errorCode: null });

  renderModal({ onSucceeded });
  await waitFor(() => expect(api.fetchWeComAvatarAuthorization).toHaveBeenCalledTimes(1));
  expect(onSucceeded).not.toHaveBeenCalled();

  await act(async () => { await vi.advanceTimersByTimeAsync(2_000); });

  await waitFor(() => expect(onSucceeded).toHaveBeenCalledTimes(1));
  await act(async () => { await vi.advanceTimersByTimeAsync(4_000); });
  expect(api.fetchWeComAvatarAuthorization).toHaveBeenCalledTimes(2);
});

it('stops polling when closed and can create a new attempt after failure', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  api.fetchWeComAvatarAuthorization.mockResolvedValue({
    authorizationId: 'authorization-1', status: 'FAILED', errorCode: 'WECOM_AVATAR_AUTH_TICKET_MISSING',
  });
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  const rendered = renderModal();

  expect(await screen.findByText('头像授权失败，请重新发起')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '重新生成授权二维码' }));
  await waitFor(() => expect(api.createWeComAvatarAuthorization).toHaveBeenCalledTimes(2));

  rendered.rerender(
    <QueryClientProvider client={new QueryClient()}>
      <WeComAvatarAuthorizationModal open={false} onClose={vi.fn()} onSucceeded={vi.fn()} />
    </QueryClientProvider>,
  );
  const calls = api.fetchWeComAvatarAuthorization.mock.calls.length;
  await act(async () => { await vi.advanceTimersByTimeAsync(4_000); });
  expect(api.fetchWeComAvatarAuthorization).toHaveBeenCalledTimes(calls);
});
