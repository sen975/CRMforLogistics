import '@testing-library/jest-dom/vitest';
import { App as AntApp } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import UserManagementPage from './UserManagementPage';

const api = vi.hoisted(() => ({
  fetchAdminUsers: vi.fn(), fetchAccountRoles: vi.fn(), replaceAdminUserRoles: vi.fn(),
  resetAdminUserPassword: vi.fn(),
}));
vi.mock('../api/endpoints', () => api);

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchAdminUsers.mockResolvedValue({
    items: [{ id: 'user-1', username: 'agent_01', displayName: '张三', status: 'active', roles: ['AGENT'], createdAt: '2026-09-03T00:00:00Z' }],
    total: 1, page: 0, size: 20,
  });
  api.fetchAccountRoles.mockResolvedValue([
    { code: 'AGENT', displayName: 'Agent' }, { code: 'ADMIN', displayName: 'Administrator' },
  ]);
});

it('loads users and exposes role and password commands', async () => {
  render(<AntApp><UserManagementPage /></AntApp>);

  await waitFor(() => expect(api.fetchAdminUsers).toHaveBeenCalledWith(0, 20));
  expect(await screen.findByText('张三')).toBeVisible();
  expect(screen.getByRole('button', { name: '调整角色' })).toBeVisible();
  expect(screen.getByRole('button', { name: '重置密码' })).toBeVisible();
});
