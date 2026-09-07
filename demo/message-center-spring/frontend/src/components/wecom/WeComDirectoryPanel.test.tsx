import '@testing-library/jest-dom/vitest';
import { App as AntApp, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import WeComDirectoryPanel from './WeComDirectoryPanel';

const api = vi.hoisted(() => ({
  listWeComDirectoryMembers: vi.fn(),
  listWeComDepartments: vi.fn(),
  listWeComTags: vi.fn(),
  listWeComExternalContacts: vi.fn(),
  getWeComExternalContact: vi.fn(),
  syncWeComDirectoryProfiles: vi.fn(),
}));

vi.mock('../../api/endpoints', () => api);

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider><AntApp><WeComDirectoryPanel authCorpId="corp-1" /></AntApp></ConfigProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  api.listWeComDirectoryMembers.mockResolvedValue({ userlist: [{ userid: 'alice', name: 'Alice' }] });
  api.listWeComDepartments.mockResolvedValue({ department: [] });
  api.listWeComTags.mockResolvedValue({ taglist: [] });
  api.listWeComExternalContacts.mockResolvedValue({ external_userid: [] });
  api.syncWeComDirectoryProfiles.mockResolvedValue({ discovered: 0 });
});

describe('WeComDirectoryPanel', () => {
  it('opens on departments without requesting members', async () => {
    renderPanel();
    expect(await screen.findByText('暂无部门')).toBeInTheDocument();
    expect(api.listWeComDepartments).toHaveBeenCalledWith('corp-1');
    expect(api.listWeComDirectoryMembers).not.toHaveBeenCalled();
  });

  it('shows the member API error after opening a department', async () => {
    api.listWeComDepartments.mockResolvedValue({ department: [{ id: 7, name: '销售一部' }] });
    api.listWeComDirectoryMembers.mockRejectedValue({
      message: 'Request failed with status code 403',
      response: { data: { code: 'WECOM_API_PERMISSION_DENIED', message: '企业微信应用缺少所需权限' } },
    });
    renderPanel();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '查看成员' }));
    expect(await screen.findByText('企业微信应用缺少所需权限')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeInTheDocument();
  });

  it('loads members only after opening a department', async () => {
    api.listWeComDepartments.mockResolvedValue({ department: [{ id: 7, name: '销售一部' }] });
    api.listWeComDirectoryMembers.mockResolvedValue({ user: [{ userid: 'bob', name: 'Bob', avatar: 'https://a/bob' }] });
    renderPanel();
    expect(api.listWeComDirectoryMembers).not.toHaveBeenCalled();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '查看成员' }));
    expect(api.listWeComDirectoryMembers).toHaveBeenCalledWith('corp-1', 7, true);
    expect(await screen.findByText('Bob')).toBeInTheDocument();
    expect(screen.getByText('bob')).toBeInTheDocument();
  });

  it('returns from department members to the department list', async () => {
    api.listWeComDepartments.mockResolvedValue({ department: [{ id: 7, name: '销售一部' }] });
    renderPanel();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '查看成员' }));
    expect(await screen.findByRole('button', { name: /返回部门/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /返回部门/ }));
    expect(await screen.findByText('销售一部')).toBeInTheDocument();
  });

  it('keeps customer loading on the bound-account customer page', async () => {
    renderPanel();
    expect(screen.queryByRole('button', { name: '查看客户' })).not.toBeInTheDocument();
    expect(api.listWeComExternalContacts).not.toHaveBeenCalled();
  });
});
