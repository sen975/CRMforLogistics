import '@testing-library/jest-dom/vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { WeComLoginPanel } from './WeComLoginPanel';

const api = vi.hoisted(() => ({ createWeComAttempt: vi.fn() }));
const sdk = vi.hoisted(() => ({ loadWeComSdk: vi.fn() }));

vi.mock('../../api/endpoints', () => api);
vi.mock('../../wecom/wecomSdk', () => sdk);

beforeEach(() => {
  vi.clearAllMocks();
  api.createWeComAttempt.mockResolvedValue({
    loginType: 'CorpApp',
    appId: 'corp-id',
    agentId: '1000247',
    redirectUri: 'https://crm.example.com/login',
    state: 'state-1',
    expiresIn: 300,
  });
});

it('mounts the official panel with the backend-issued attempt', async () => {
  const createWWLoginPanel = vi.fn();
  sdk.loadWeComSdk.mockResolvedValue({ createWWLoginPanel });
  const onAuthenticated = vi.fn();

  render(<WeComLoginPanel purpose="login" onAuthenticated={onAuthenticated} />);

  await waitFor(() => expect(createWWLoginPanel).toHaveBeenCalledTimes(1));
  const options = createWWLoginPanel.mock.calls[0][0];
  expect(options).toEqual(expect.objectContaining({
    el: expect.stringMatching(/^#/),
    params: expect.objectContaining({
      login_type: 'CorpApp',
      appid: 'corp-id',
      agentid: '1000247',
      redirect_uri: 'https://crm.example.com/login',
      state: 'state-1',
      redirect_type: 'callback',
      panel_size: 'small',
      lang: 'zh',
    }),
  }));
  expect(options.params).not.toHaveProperty('scope');
  await act(async () => options.onLoginSuccess({ code: 'login-code' }));
  expect(onAuthenticated).toHaveBeenCalledWith('login-code', 'state-1');
  expect(screen.getByLabelText('企业微信官方登录组件')).toBeInTheDocument();
});

it('shows a compact retryable error when the official SDK fails', async () => {
  sdk.loadWeComSdk.mockRejectedValue(new Error('network'));

  render(<WeComLoginPanel purpose="login" onAuthenticated={vi.fn()} />);

  expect(await screen.findByText('企业微信登录组件加载失败，请重试')).toBeVisible();
});
