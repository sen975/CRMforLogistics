import '@testing-library/jest-dom/vitest';
import { App as AntApp } from 'antd';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { AccountPanel } from './AccountPanel';

/** 头像来源是可变的（用例要切到 UPLOAD），所以这里显式给联合类型，别让字面量收窄成 `'INITIAL'`。 */
type TestAvatar = {
  source: 'WECOM' | 'UPLOAD' | 'INITIAL';
  contentUrl: string | null;
  initial: string;
  revision: string | null;
};

const auth = vi.hoisted(() => ({
  profile: {
    id: 'user-1', username: 'agent_01', displayName: '张三', roles: ['AGENT'],
    avatar: { source: 'INITIAL', contentUrl: null, initial: '张', revision: null } as TestAvatar,
  },
  refreshProfile: vi.fn(),
  replaceSession: vi.fn(),
}));

vi.mock('../hooks/useAuth', () => ({ useAuth: () => auth }));
vi.mock('../api/endpoints', () => ({
  updateAccountProfile: vi.fn(), uploadAccountAvatar: vi.fn(), deleteAccountAvatar: vi.fn(),
  fetchAccountAvatarBlob: vi.fn(() => Promise.resolve(new Blob())), changeAccountPassword: vi.fn(),
}));
vi.mock('./wecom/WeComBindingPanel', () => ({ WeComBindingPanel: () => <div>企业微信绑定</div> }));

beforeEach(() => {
  vi.clearAllMocks();
  // jsdom 不实现这两个方法；UPLOAD 头像会把 blob 转成 object URL。
  URL.createObjectURL = vi.fn(() => 'blob:avatar');
  URL.revokeObjectURL = vi.fn();
  auth.profile = {
    id: 'user-1', username: 'agent_01', displayName: '张三', roles: ['AGENT'],
    avatar: { source: 'INITIAL', contentUrl: null, initial: '张', revision: null },
  };
});

it('shows account identity and profile maintenance forms', () => {
  render(<AntApp><AccountPanel /></AntApp>);

  expect(screen.getByText('张三')).toBeVisible();
  expect(screen.getByText('agent_01')).toBeVisible();
  expect(screen.getByText('张')).toBeVisible();
  expect(screen.getByText('文字头像')).toBeVisible();
  expect(screen.getByRole('button', { name: '保存昵称' })).toBeVisible();
  expect(screen.getByRole('button', { name: '重新设置密码' })).toBeVisible();
  expect(screen.getByText('企业微信绑定')).toBeVisible();
});

/**
 * 密码不是「页面上的一个表单」，而是一个按钮后面的弹窗 ——
 * 三个密码框不该常驻在抽屉里，占着版面还容易被当成待填表。
 */
it('keeps the password form behind a button instead of on the page', async () => {
  render(<AntApp><AccountPanel /></AntApp>);

  expect(screen.queryByPlaceholderText('当前登录密码')).not.toBeInTheDocument();
  expect(screen.queryByPlaceholderText('至少 8 个字符')).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: '重新设置密码' }));

  expect(await screen.findByPlaceholderText('当前登录密码')).toBeInTheDocument();
  expect(screen.getByPlaceholderText('至少 8 个字符')).toBeInTheDocument();
  expect(screen.getByPlaceholderText('再输入一次新密码')).toBeInTheDocument();

  // 弹窗里的表单要是「活的」：空着点确认，三条必填校验都得报出来。
  fireEvent.click(screen.getByRole('button', { name: '确认修改' }));

  expect(await screen.findByText('请输入原密码')).toBeInTheDocument();
  expect(screen.getByText('请输入新密码')).toBeInTheDocument();
  expect(screen.getByText('请再次输入新密码')).toBeInTheDocument();
});

/**
 * 「删除上传头像」只对上传头像有意义：后端在 `avatarObjectKey` 为空时静默 no-op，
 * 无条件显示这个按钮 ⇒ 点了会弹出「已删除上传头像」的成功提示，而头像纹丝不动。
 */
it('only offers avatar removal when the pictured avatar came from an upload', () => {
  const initial = render(<AntApp><AccountPanel /></AntApp>);

  // 按文本查而不是按 role name 查 —— 带图标的 antd Button 的可访问名会拼上图标名
  // （实测「upload上传头像」），用 role name 匹配会漏掉或误命中。
  expect(screen.queryByText('删除上传头像')).not.toBeInTheDocument();
  expect(screen.getByText('文字头像')).toBeVisible();
  initial.unmount();

  auth.profile = {
    ...auth.profile,
    avatar: { source: 'UPLOAD', contentUrl: '/api/account/avatar/content', initial: '张', revision: '1' },
  };
  render(<AntApp><AccountPanel /></AntApp>);

  expect(screen.getByText('上传头像', { selector: '.mc-account-chip' })).toBeVisible();
  expect(screen.getByText('删除上传头像')).toBeVisible();
});
