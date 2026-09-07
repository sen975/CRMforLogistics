import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AppLayout from './AppLayout';

const authState = vi.hoisted(() => ({ username: 'admin', isAdmin: true, canBroadcast: true, logout: vi.fn() }));

vi.mock('../hooks/useAuth', () => ({
  useAuth: () => authState,
}));
vi.mock('../pages/ContactsPage', () => ({ default: () => <div>联系人列表</div> }));
vi.mock('./ContactDetailPanel', () => ({ default: () => <div>联系人详情</div> }));
vi.mock('./CallRecordDetail', () => ({ default: () => <div>通话详情</div> }));
vi.mock('./WeComGroupDetailPanel', () => ({ default: () => <div>群 Topic 详情</div> }));
vi.mock('./AccountPanel', () => ({ AccountPanel: () => <div>账号</div> }));
vi.mock('./AccountAvatar', () => ({ AccountAvatar: () => <div>头像</div> }));

beforeEach(() => {
  authState.username = 'admin';
  authState.isAdmin = true;
  authState.canBroadcast = true;
  authState.logout.mockClear();
});

it('groups top-level navigation by channel and system settings', () => {
  render(<MemoryRouter><AppLayout /></MemoryRouter>);
  expect(screen.getByRole('button', { name: /WhatsApp/ })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /企业微信/ })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /电话/ })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /系统设置/ })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /^发送$/ })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /^模板$/ })).not.toBeInTheDocument();
});

it('shows channel and system actions in their dropdowns', async () => {
  render(<MemoryRouter><AppLayout /></MemoryRouter>);
  const user = userEvent.setup();

  await user.click(screen.getByRole('button', { name: /WhatsApp/ }));
  expect(screen.getByRole('menuitem', { name: '模板' })).toBeInTheDocument();
  expect(screen.getByRole('menuitem', { name: '群发' })).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: /系统设置/ }));
  expect(screen.getByRole('menuitem', { name: '发送' })).toBeInTheDocument();
  expect(screen.getByRole('menuitem', { name: 'Topic 仓库' })).toBeInTheDocument();
  expect(screen.getByRole('menuitem', { name: '用户管理' })).toBeInTheDocument();
  expect(screen.getByRole('menuitem', { name: '账号' })).toBeInTheDocument();
  expect(screen.getByRole('menuitem', { name: '退出登录' })).toBeInTheDocument();
});

it('filters administrator-only and broadcast actions by permission', async () => {
  authState.isAdmin = false;
  authState.canBroadcast = false;
  render(<MemoryRouter><AppLayout /></MemoryRouter>);
  const user = userEvent.setup();

  expect(screen.queryByRole('button', { name: /企业微信/ })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: /WhatsApp/ }));
  expect(screen.getByRole('menuitem', { name: '模板' })).toBeInTheDocument();
  expect(screen.queryByRole('menuitem', { name: '群发' })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: /系统设置/ }));
  expect(screen.getByRole('menuitem', { name: '发送' })).toBeInTheDocument();
  expect(screen.getByRole('menuitem', { name: '渠道设置' })).toBeInTheDocument();
  expect(screen.queryByRole('menuitem', { name: '用户管理' })).not.toBeInTheDocument();
});

it('exposes the WeCom management entry for administrators', () => {
  render(<MemoryRouter><AppLayout /></MemoryRouter>);
  expect(screen.getByRole('button', { name: /企业微信/ })).toBeInTheDocument();
});

it('reuses the shared detail panel toggle for a WeCom group Topic timeline', () => {
  render(
    <MemoryRouter initialEntries={['/conversations/wecom-group/group-1']}>
      <AppLayout />
    </MemoryRouter>,
  );

  const toggle = screen.getByRole('button', { name: '展开右侧栏' });
  fireEvent.click(toggle);

  expect(screen.getByRole('button', { name: '收起右侧栏' })).toBeInTheDocument();
  expect(screen.getByText('群 Topic 详情')).toBeInTheDocument();
});

it('labels the shared mobile detail drawer as a group Topic panel on group routes', () => {
  const originalMatchMedia = window.matchMedia;
  window.matchMedia = (query: string) => ({
    matches: query === '(max-width: 767px)',
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  });

  try {
    render(
      <MemoryRouter initialEntries={['/conversations/wecom-group/group-1']}>
        <AppLayout />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '展开右侧栏' }));
    expect(screen.getByText('群 Topic')).toBeInTheDocument();
  } finally {
    window.matchMedia = originalMatchMedia;
  }
});
