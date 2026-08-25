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
});

describe('WeComDirectoryPanel', () => {
  it('renders the member name returned by the directory API', async () => {
    renderPanel();
    expect(await screen.findByText('Alice')).toBeInTheDocument();
    expect(api.listWeComDirectoryMembers).toHaveBeenCalledWith('corp-1', 1, true);
  });

  it('shows the API error and a retry action instead of an empty directory', async () => {
    api.listWeComDirectoryMembers.mockRejectedValue({
      message: 'Request failed with status code 403',
      response: { data: { code: 'WECOM_API_PERMISSION_DENIED', message: '企业微信应用缺少所需权限' } },
    });
    renderPanel();
    expect(await screen.findByText('企业微信应用缺少所需权限')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeInTheDocument();
  });

  it('renders members when the upstream uses the singular user field', async () => {
    api.listWeComDirectoryMembers.mockResolvedValue({ user: [{ userid: 'bob', name: 'Bob', avatar: 'https://a/bob' }] });
    renderPanel();
    expect(await screen.findByText('Bob')).toBeInTheDocument();
    expect(screen.getByText('bob')).toBeInTheDocument();
  });

  it('lets an administrator open members from a department row', async () => {
    api.listWeComDepartments.mockResolvedValue({ department: [{ id: 7, name: '销售一部' }] });
    renderPanel();
    const user = userEvent.setup();
    await user.click(await screen.findByTitle('部门'));
    expect(await screen.findByText('销售一部')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '查看成员' }));
    expect(api.listWeComDirectoryMembers).toHaveBeenLastCalledWith('corp-1', 7, true);
  });
});
