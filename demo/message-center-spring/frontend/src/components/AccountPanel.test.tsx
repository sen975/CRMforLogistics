import '@testing-library/jest-dom/vitest';
import { App as AntApp } from 'antd';
import { render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { AccountPanel } from './AccountPanel';

const auth = vi.hoisted(() => ({
  profile: {
    id: 'user-1', username: 'agent_01', displayName: '张三', roles: ['AGENT'],
    avatar: { source: 'INITIAL' as const, contentUrl: null, initial: '张', revision: null },
  },
  refreshProfile: vi.fn(),
  replaceSession: vi.fn(),
}));

vi.mock('../hooks/useAuth', () => ({ useAuth: () => auth }));
vi.mock('../api/endpoints', () => ({
  updateAccountProfile: vi.fn(), uploadAccountAvatar: vi.fn(), deleteAccountAvatar: vi.fn(),
  fetchAccountAvatarBlob: vi.fn(), changeAccountPassword: vi.fn(),
}));
vi.mock('./wecom/WeComBindingPanel', () => ({ WeComBindingPanel: () => <div>企业微信绑定</div> }));

beforeEach(() => vi.clearAllMocks());

it('shows account identity and profile maintenance forms', () => {
  render(<AntApp><AccountPanel /></AntApp>);

  expect(screen.getByText('张三')).toBeVisible();
  expect(screen.getByText('agent_01')).toBeVisible();
  expect(screen.getByText('张')).toBeVisible();
  expect(screen.getByRole('button', { name: '保存昵称' })).toBeVisible();
  expect(screen.getByRole('button', { name: '修改密码' })).toBeVisible();
  expect(screen.getByText('企业微信绑定')).toBeVisible();
});
