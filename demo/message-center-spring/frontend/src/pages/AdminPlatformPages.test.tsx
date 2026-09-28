import '@testing-library/jest-dom/vitest';
import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import type { AdminCamsScope, AdminScopedWhatsAppAccount } from '../api/types';
import { router } from '../router';
import AdminPlatformsPage from './AdminPlatformsPage';
import AdminWhatsAppAccountsPage from './AdminWhatsAppAccountsPage';

const api = vi.hoisted(() => ({
  fetchAdminCams: vi.fn(),
  createAdminCams: vi.fn(),
  updateAdminCams: vi.fn(),
  blockAdminCams: vi.fn(),
  testAdminCams: vi.fn(),
  syncAdminCams: vi.fn(),
  fetchAdminScopedWhatsAppAccounts: vi.fn(),
  assignAdminScopedWhatsAppAccount: vi.fn(),
  transferAdminScopedWhatsAppAccount: vi.fn(),
  reclaimAdminScopedWhatsAppAccount: vi.fn(),
  fetchAdminScopedAssignmentHistory: vi.fn(),
  fetchAdminWhatsAppCallbacks: vi.fn(),
  updateAdminWhatsAppPhoneCallback: vi.fn(),
  updateAdminWhatsAppAccountCallback: vi.fn(),
  fetchAdminUsers: vi.fn(),
  fetchWhatsAppAccounts: vi.fn(),
  fetchAccountProfile: vi.fn(),
}));

vi.mock('../api/endpoints', () => api);
vi.mock('../components/AppLayout', async () => {
  const { Outlet } = await import('react-router-dom');
  return { default: () => <Outlet /> };
});

const readyScope: AdminCamsScope = {
  scopeId: 'cams-1',
  configured: true,
  displayName: '东南亚空间',
  custSpaceId: 'cust-1',
  accessKeyIdMasked: 'LTAI****',
  region: 'ap-southeast-1',
  endpoint: 'cams.ap-southeast-1.aliyuncs.com',
  status: 'READY',
  version: 7,
  lastTestedAt: null,
  lastTestStatus: null,
  lastTestErrorCode: null,
  lastSyncedAt: null,
  lastSyncStatus: null,
  lastSyncErrorCode: null,
  source: 'MANUAL',
  scopeType: 'ENTERPRISE_API',
  ownerUserId: null,
};

const blockedScope: AdminCamsScope = {
  ...readyScope,
  scopeId: 'cams-2',
  displayName: '已停用空间',
  custSpaceId: 'cust-2',
  status: 'BLOCKED',
  version: 3,
};

const unassignedAccount: AdminScopedWhatsAppAccount = {
  accountId: 'a-1',
  ownerUserId: null,
  maskedPhone: '+86 138****0001',
  name: '号码一',
  providerStatus: 'ACTIVE',
  verificationStatus: 'VERIFIED',
  version: 4,
};

const assignedAccount: AdminScopedWhatsAppAccount = {
  ...unassignedAccount,
  accountId: 'a-2',
  ownerUserId: 'u-2',
  maskedPhone: '+86 138****0002',
  name: '号码二',
  version: 5,
};

// CAMS has not activated this number yet, so it is the one the page must not offer as sendable.
// The code verification is not part of that call.
const pendingAccount: AdminScopedWhatsAppAccount = {
  ...unassignedAccount,
  accountId: 'a-3',
  maskedPhone: '+86 138****0003',
  name: '号码三',
  providerStatus: 'PENDING',
  verificationStatus: 'PENDING',
  version: 2,
};

const salesUser = { id: 'u-2', username: 'sales_a', displayName: '李四', status: 'active', roles: ['AGENT'], createdAt: '' };
const adminUser = { id: 'u-9', username: 'admin_ops', displayName: '王五', status: 'active', roles: ['ADMIN'], createdAt: '' };

function renderPage(ui: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ConfigProvider locale={zhCN}>
          <AntApp>{ui}</AntApp>
        </ConfigProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function renderWithRouter() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider locale={zhCN}>
        <AntApp><RouterProvider router={router} /></AntApp>
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

function signIn(roles: string[]) {
  localStorage.setItem('token', 'test-token');
  localStorage.setItem('roles', JSON.stringify(roles));
  api.fetchAccountProfile.mockResolvedValue({ id: 'u-1', username: 'admin_01', displayName: '管理员', roles, avatar: null });
}

function rowOf(text: string): HTMLElement {
  const cell = screen.getByText(text);
  const row = cell.closest('tr');
  if (!row) throw new Error(`no table row for ${text}`);
  return row as HTMLElement;
}

async function openRowAction(user: ReturnType<typeof userEvent.setup>, rowText: string, label: RegExp) {
  await user.click(within(rowOf(rowText)).getByRole('button', { name: label }));
  return screen.findByRole('dialog');
}

// With virtual scrolling rc-select exposes only hidden aria nodes as role="option";
// the visible dropdown label is the element that actually carries the click handler.
async function pickSelectOption(user: ReturnType<typeof userEvent.setup>, label: string) {
  await user.click(await screen.findByText(label));
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  api.fetchAdminCams.mockResolvedValue([]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([]);
  api.fetchAdminUsers.mockResolvedValue({ items: [salesUser, adminUser], total: 2, page: 0, size: 100 });
  api.fetchWhatsAppAccounts.mockResolvedValue([]);
  api.fetchAdminScopedAssignmentHistory.mockResolvedValue([]);
  api.fetchAdminWhatsAppCallbacks.mockResolvedValue({
    scopeId: 'cams-1',
    phoneConfigs: [],
    accountConfig: null,
    ingressPath: '/api/v1/webhooks/chatapp',
  });
});

it('管理员访问 /admin/platforms 看到平台接入管理', async () => {
  signIn(['ADMIN']);
  await router.navigate('/admin/platforms');
  renderWithRouter();

  expect(await screen.findByRole('heading', { name: '平台接入管理' })).toBeVisible();
});

it('非管理员访问 /admin/platforms 被拒绝且看不到平台接入管理', async () => {
  signIn(['AGENT']);
  await router.navigate('/admin/platforms');
  renderWithRouter();

  expect(await screen.findByText('无权访问')).toBeVisible();
  expect(screen.queryByText('平台接入管理')).not.toBeInTheDocument();
});

it('平台页展示每个 CAMS 的名称、空间标识和状态', async () => {
  api.fetchAdminCams.mockResolvedValue([readyScope, blockedScope]);
  renderPage(<AdminPlatformsPage />);

  expect(await screen.findByText('东南亚空间')).toBeVisible();
  expect(screen.getByText('cust-1')).toBeVisible();
  expect(screen.getByText('已停用空间')).toBeVisible();
  expect(screen.getByText('cust-2')).toBeVisible();
  expect(screen.getByText('可用')).toBeVisible();
  expect(screen.getByText('已停用')).toBeVisible();
});

it('停用按钮只出现在可用空间上，并按服务端返回的版本提交', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope, blockedScope]);
  api.blockAdminCams.mockResolvedValue({ ...readyScope, status: 'BLOCKED' });
  renderPage(<AdminPlatformsPage />);

  await screen.findByText('东南亚空间');
  const blockButtons = screen.getAllByRole('button', { name: /停\s*用/ });
  expect(blockButtons).toHaveLength(1);

  await user.click(blockButtons[0]);
  const confirmButtons = await screen.findAllByRole('button', { name: /停\s*用/ });
  await user.click(confirmButtons[confirmButtons.length - 1]);

  await waitFor(() => expect(api.blockAdminCams).toHaveBeenCalledTimes(1));
  expect(api.blockAdminCams).toHaveBeenCalledWith('cams-1', 7);
});

it('编辑弹窗回填非密钥字段，密钥留空表示保留原值', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.updateAdminCams.mockResolvedValue(readyScope);
  renderPage(<AdminPlatformsPage />);

  await screen.findByText('东南亚空间');
  await user.click(screen.getByRole('button', { name: /编\s*辑/ }));
  const dialog = await screen.findByRole('dialog');

  expect(within(dialog).getByLabelText('显示名称')).toHaveValue('东南亚空间');
  expect(within(dialog).getByLabelText('CustSpaceId')).toHaveValue('cust-1');
  expect(within(dialog).getByLabelText('Region')).toHaveValue('ap-southeast-1');
  expect(within(dialog).getByLabelText('Endpoint')).toHaveValue('cams.ap-southeast-1.aliyuncs.com');

  const secret = within(dialog).getByLabelText('AccessKey Secret');
  expect(secret).toHaveValue('');
  expect(secret).toHaveAttribute('placeholder', '留空保留原 Secret');
  expect(within(dialog).getByLabelText('AccessKey ID')).toHaveValue('');

  await user.click(within(dialog).getByRole('button', { name: /确\s*定/ }));

  await waitFor(() => expect(api.updateAdminCams).toHaveBeenCalledTimes(1));
  const [scopeId, payload] = api.updateAdminCams.mock.calls[0];
  expect(scopeId).toBe('cams-1');
  expect(payload).toEqual({
    displayName: '东南亚空间',
    custSpaceId: 'cust-1',
    accessKeyId: '',
    accessKeySecret: '',
    region: 'ap-southeast-1',
    endpoint: 'cams.ap-southeast-1.aliyuncs.com',
    expectedVersion: 7,
    scopeType: 'ENTERPRISE_API',
    ownerUserId: null,
  });
});

const SECOND_CAMS_TITLE = '再接入一个企业级 CAMS 空间？';

// antd renders the confirm title twice (visible node + an aria mirror), so target the visible one.
const CONFIRM_TITLE = { selector: '.ant-modal-confirm-title' } as const;
const CONFIRM_BODY = { selector: '.ant-modal-confirm-content' } as const;

function confirmAction(label: RegExp): HTMLElement | null {
  return screen.queryByRole('button', { name: label });
}

// jsdom never fires transitionend, so a dismissed antd confirm stays mounted in its leave
// state instead of being removed. Reacting to the leave class proves the dismissal landed.
function confirmIsDismissing(): boolean {
  return document.querySelector('.ant-modal-confirm.ant-zoom-leave') !== null;
}

async function submitCreateForm(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /添加 CAMS/ }));
  const dialog = await screen.findByRole('dialog');
  await user.type(within(dialog).getByLabelText('显示名称'), '第二空间');
  await user.type(within(dialog).getByLabelText('CustSpaceId'), 'cust-2');
  await user.type(within(dialog).getByLabelText('AccessKey ID'), 'LTAI2');
  await user.type(within(dialog).getByLabelText('AccessKey Secret'), 'secret-2');
  await user.click(within(dialog).getByRole('button', { name: /确\s*定/ }));
}

it('已存在企业级空间时，提交新增表单会先弹出共享模板目录不可用的确认', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  renderPage(<AdminPlatformsPage />);

  await screen.findByText('东南亚空间');
  await submitCreateForm(user);

  expect(await screen.findByText(SECOND_CAMS_TITLE, CONFIRM_TITLE)).toBeInTheDocument();
  expect(screen.getByText(/WHATSAPP_PROVIDER_SCOPE_MISMATCH/, CONFIRM_BODY)).toBeInTheDocument();
});

it('新增 Business App 空间时不需要归属人以外的确认，也不受企业级空间警告拦截', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.createAdminCams.mockResolvedValue({ ...readyScope, scopeId: 'cams-2', custSpaceId: 'cust-2', scopeType: 'EMPLOYEE_BUSINESS_APP', ownerUserId: 'u-2' });
  renderPage(<AdminPlatformsPage />);

  await screen.findByText('东南亚空间');
  await user.click(screen.getByRole('button', { name: /添加 CAMS/ }));
  const dialog = await screen.findByRole('dialog');
  await user.type(within(dialog).getByLabelText('显示名称'), '小森');
  await user.type(within(dialog).getByLabelText('CustSpaceId'), 'cust-2');
  await user.click(within(dialog).getByRole('combobox', { name: '空间类型' }));
  await pickSelectOption(user, 'Business App 共存');
  // The field's tooltip icon joins the accessible name, so match loosely.
  await user.click(within(dialog).getByRole('combobox', { name: /归属人/ }));
  await pickSelectOption(user, '李四');
  await user.type(within(dialog).getByLabelText('AccessKey ID'), 'LTAI2');
  await user.type(within(dialog).getByLabelText('AccessKey Secret'), 'secret-2');
  await user.click(within(dialog).getByRole('button', { name: /确\s*定/ }));

  await waitFor(() => expect(api.createAdminCams).toHaveBeenCalledTimes(1));
  expect(api.createAdminCams).toHaveBeenCalledWith({
    displayName: '小森',
    custSpaceId: 'cust-2',
    accessKeyId: 'LTAI2',
    accessKeySecret: 'secret-2',
    region: 'ap-southeast-1',
    endpoint: 'cams.ap-southeast-1.aliyuncs.com',
    scopeType: 'EMPLOYEE_BUSINESS_APP',
    ownerUserId: 'u-2',
  });
  expect(screen.queryByText(SECOND_CAMS_TITLE, CONFIRM_TITLE)).not.toBeInTheDocument();
});

it('取消确认不发起任何新增请求', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.createAdminCams.mockResolvedValue({ ...readyScope, scopeId: 'cams-2', custSpaceId: 'cust-2' });
  renderPage(<AdminPlatformsPage />);

  await screen.findByText('东南亚空间');
  await submitCreateForm(user);
  await screen.findByText(SECOND_CAMS_TITLE, CONFIRM_TITLE);

  await user.click(confirmAction(/暂不添加/)!);

  await waitFor(() => expect(confirmIsDismissing()).toBe(true));
  expect(api.createAdminCams).not.toHaveBeenCalled();
  // The create form itself stays put so the administrator can revisit the decision.
  expect(screen.getByText('添加 CAMS', { selector: '.ant-modal-title' })).toBeInTheDocument();
});

it('确认后才真正发起新增请求', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.createAdminCams.mockResolvedValue({ ...readyScope, scopeId: 'cams-2', custSpaceId: 'cust-2' });
  renderPage(<AdminPlatformsPage />);

  await screen.findByText('东南亚空间');
  await submitCreateForm(user);
  await screen.findByText(SECOND_CAMS_TITLE, CONFIRM_TITLE);
  expect(api.createAdminCams).not.toHaveBeenCalled();

  await user.click(confirmAction(/确认添加/)!);

  await waitFor(() => expect(api.createAdminCams).toHaveBeenCalledTimes(1));
  expect(api.createAdminCams).toHaveBeenCalledWith({
    displayName: '第二空间',
    custSpaceId: 'cust-2',
    accessKeyId: 'LTAI2',
    accessKeySecret: 'secret-2',
    region: 'ap-southeast-1',
    endpoint: 'cams.ap-southeast-1.aliyuncs.com',
    scopeType: 'ENTERPRISE_API',
    ownerUserId: null,
  });
});

it('全新安装没有任何空间时，新增不会被确认拦截', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([]);
  api.createAdminCams.mockResolvedValue({ ...readyScope, scopeId: 'cams-1', custSpaceId: 'cust-2' });
  renderPage(<AdminPlatformsPage />);

  await screen.findByText('暂无 CAMS 配置');
  await submitCreateForm(user);

  await waitFor(() => expect(api.createAdminCams).toHaveBeenCalledTimes(1));
  expect(screen.queryByText(SECOND_CAMS_TITLE, CONFIRM_TITLE)).not.toBeInTheDocument();
});

it('测试与同步对停用空间不可点，对可用空间调用对应接口', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope, blockedScope]);
  api.testAdminCams.mockResolvedValue({ success: true, phoneCount: 2, message: '连接成功' });
  api.syncAdminCams.mockResolvedValue({ importedCount: 1, refreshedCount: 2, unavailableCount: 0, providerPhones: [], accounts: [] });
  renderPage(<AdminPlatformsPage />);

  await screen.findByText('东南亚空间');
  expect(within(rowOf('已停用空间')).getByRole('button', { name: /测\s*试/ })).toBeDisabled();
  expect(within(rowOf('已停用空间')).getByRole('button', { name: /同\s*步/ })).toBeDisabled();

  await user.click(within(rowOf('东南亚空间')).getByRole('button', { name: /测\s*试/ }));
  await waitFor(() => expect(api.testAdminCams).toHaveBeenCalledWith('cams-1'));

  await user.click(within(rowOf('东南亚空间')).getByRole('button', { name: /同\s*步/ }));
  await waitFor(() => expect(api.syncAdminCams).toHaveBeenCalledWith('cams-1'));
});

it('账号页自动选中第一个可用 CAMS 并按该空间查询账号', async () => {
  api.fetchAdminCams.mockResolvedValue([blockedScope, readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([unassignedAccount]);
  renderPage(<AdminWhatsAppAccountsPage />);

  await waitFor(() => expect(api.fetchAdminScopedWhatsAppAccounts).toHaveBeenCalledWith('cams-1'));
  expect(await screen.findByText('号码一')).toBeVisible();
  expect(await screen.findByRole('region', { name: 'CAMS 回调地址' })).toBeVisible();
  expect(api.fetchAdminWhatsAppCallbacks).toHaveBeenCalledWith('cams-1');
});

it('切换 CAMS 后按新空间重新查询账号', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([blockedScope, readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([unassignedAccount]);
  renderPage(<AdminWhatsAppAccountsPage />);

  await waitFor(() => expect(api.fetchAdminScopedWhatsAppAccounts).toHaveBeenCalledWith('cams-1'));
  await user.click(screen.getByRole('combobox', { name: '选择 CAMS' }));
  await pickSelectOption(user, '已停用空间（cust-2）');

  await waitFor(() => expect(api.fetchAdminScopedWhatsAppAccounts).toHaveBeenLastCalledWith('cams-2'));
  expect(await screen.findByText('当前 CAMS 已停用')).toBeVisible();
});

it('未分配的行只能分配，已分配的行只能转交和收回', async () => {
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([unassignedAccount, assignedAccount]);
  renderPage(<AdminWhatsAppAccountsPage />);

  await screen.findByText('号码一');
  const unassignedRow = rowOf('号码一');
  expect(within(unassignedRow).getByRole('button', { name: /分\s*配/ })).toBeVisible();
  expect(within(unassignedRow).queryByRole('button', { name: /收\s*回/ })).not.toBeInTheDocument();

  const assignedRow = rowOf('号码二');
  expect(within(assignedRow).getByRole('button', { name: /转\s*交/ })).toBeVisible();
  expect(within(assignedRow).getByRole('button', { name: /收\s*回/ })).toBeVisible();
  expect(within(assignedRow).queryByRole('button', { name: /分\s*配/ })).not.toBeInTheDocument();
});

it('分配带上空间、账号和账号版本提交', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([unassignedAccount]);
  api.assignAdminScopedWhatsAppAccount.mockResolvedValue(assignedAccount);
  renderPage(<AdminWhatsAppAccountsPage />);

  await screen.findByText('号码一');
  const dialog = await openRowAction(user, '号码一', /分\s*配/);
  await user.click(within(dialog).getByRole('combobox', { name: '目标用户' }));
  await pickSelectOption(user, '李四（sales_a）');
  await user.type(within(dialog).getByLabelText('原因'), '新入职销售');
  await user.click(within(dialog).getByRole('button', { name: /分\s*配/ }));

  await waitFor(() => expect(api.assignAdminScopedWhatsAppAccount).toHaveBeenCalledTimes(1));
  expect(api.assignAdminScopedWhatsAppAccount).toHaveBeenCalledWith('cams-1', 'a-1', {
    reason: '新入职销售',
    expectedVersion: 4,
    targetOwnerId: 'u-2',
  });
});

it('转交带上空间、账号和账号版本提交', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([assignedAccount]);
  api.transferAdminScopedWhatsAppAccount.mockResolvedValue(assignedAccount);
  renderPage(<AdminWhatsAppAccountsPage />);

  await screen.findByText('号码二');
  const dialog = await openRowAction(user, '号码二', /转\s*交/);
  await user.click(within(dialog).getByRole('combobox', { name: '目标用户' }));
  await pickSelectOption(user, '李四（sales_a）');
  await user.type(within(dialog).getByLabelText('原因'), '区域调整');
  await user.click(within(dialog).getByRole('button', { name: /转\s*交/ }));

  await waitFor(() => expect(api.transferAdminScopedWhatsAppAccount).toHaveBeenCalledTimes(1));
  expect(api.transferAdminScopedWhatsAppAccount).toHaveBeenCalledWith('cams-1', 'a-2', {
    reason: '区域调整',
    expectedVersion: 5,
    targetOwnerId: 'u-2',
  });
});

it('收回不需要目标用户，带上空间、账号和账号版本提交', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([assignedAccount]);
  api.reclaimAdminScopedWhatsAppAccount.mockResolvedValue({ ...assignedAccount, ownerUserId: null });
  renderPage(<AdminWhatsAppAccountsPage />);

  await screen.findByText('号码二');
  const dialog = await openRowAction(user, '号码二', /收\s*回/);
  expect(within(dialog).queryByRole('combobox', { name: '目标用户' })).not.toBeInTheDocument();
  await user.type(within(dialog).getByLabelText('原因'), '人员离职');
  await user.click(within(dialog).getByRole('button', { name: /收\s*回/ }));

  await waitFor(() => expect(api.reclaimAdminScopedWhatsAppAccount).toHaveBeenCalledTimes(1));
  expect(api.reclaimAdminScopedWhatsAppAccount).toHaveBeenCalledWith('cams-1', 'a-2', {
    reason: '人员离职',
    expectedVersion: 5,
  });
});

it('历史按空间和账号查询并展示分配记录', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([unassignedAccount]);
  api.fetchAdminScopedAssignmentHistory.mockResolvedValue([{
    auditId: 'h-1',
    previousOwnerUserId: null,
    nextOwnerUserId: 'u-2',
    actorUserId: 'u-1',
    action: 'ASSIGN',
    reason: '历史原因',
    createdAt: '2026-01-01T00:00:00Z',
  }]);
  renderPage(<AdminWhatsAppAccountsPage />);

  await screen.findByText('号码一');
  await user.click(within(rowOf('号码一')).getByRole('button', { name: /历\s*史/ }));

  await waitFor(() => expect(api.fetchAdminScopedAssignmentHistory).toHaveBeenCalledWith('cams-1', 'a-1'));
  expect(await screen.findByText('+86 138****0001 的分配历史')).toBeInTheDocument();
  expect(await screen.findByText('历史原因')).toBeInTheDocument();
});

it('同步当前 CAMS 只在选中可用空间时可用，并按该空间提交', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([blockedScope, readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([]);
  // The number CAMS answered for is what explains an empty space, so the page has to name it —
  // along with the statuses CAMS used, which are not the CRM's own vocabulary.
  api.syncAdminCams.mockResolvedValue({
    importedCount: 0, refreshedCount: 0, unavailableCount: 0,
    providerPhones: [{ maskedPhone: '*********9485', providerStatus: 'PENDING', verificationStatus: 'NOT_VERIFIED', accepted: false }],
    accounts: [],
  });
  renderPage(<AdminWhatsAppAccountsPage />);

  await waitFor(() => expect(api.fetchAdminScopedWhatsAppAccounts).toHaveBeenCalledWith('cams-1'));
  const syncButton = screen.getByRole('button', { name: /同步当前 CAMS/ });
  expect(syncButton).toBeEnabled();

  await user.click(syncButton);
  await waitFor(() => expect(api.syncAdminCams).toHaveBeenCalledWith('cams-1'));
  expect(await screen.findByText(/CAMS 返回但未导入 1 个号码：\*+9485（PENDING \/ NOT_VERIFIED）/)).toBeInTheDocument();

  await user.click(screen.getByRole('combobox', { name: '选择 CAMS' }));
  await pickSelectOption(user, '已停用空间（cust-2）');
  await waitFor(() => expect(syncButton).toBeDisabled());
  // The summary belongs to the space it came from, so switching spaces drops it.
  expect(screen.queryByText(/CAMS 返回但未导入/)).not.toBeInTheDocument();
});

it('恰好一个可用号码时给出自动选择提示', async () => {
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([unassignedAccount, pendingAccount]);
  renderPage(<AdminWhatsAppAccountsPage />);

  expect(await screen.findByText(/已自动选择唯一可用号码/)).toBeVisible();
});

it('代码验证未完成的号码只要 CAMS 报 ACTIVE 就照样可选', async () => {
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([
    { ...unassignedAccount, verificationStatus: 'PENDING' },
  ]);
  renderPage(<AdminWhatsAppAccountsPage />);

  expect(await screen.findByText(/已自动选择唯一可用号码/)).toBeVisible();
});

it('存在两个可用号码时不给自动选择提示', async () => {
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([unassignedAccount, assignedAccount]);
  renderPage(<AdminWhatsAppAccountsPage />);

  await screen.findByText('号码一');
  expect(screen.queryByText(/已自动选择唯一可用号码/)).not.toBeInTheDocument();
});

it('管理员账号同样是可分配的目标用户', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([unassignedAccount]);
  renderPage(<AdminWhatsAppAccountsPage />);

  await screen.findByText('号码一');
  const dialog = await openRowAction(user, '号码一', /分\s*配/);
  await user.click(within(dialog).getByRole('combobox', { name: '目标用户' }));

  expect(await screen.findByText('李四（sales_a）')).toBeInTheDocument();
  expect(screen.getByText('王五（admin_ops）')).toBeInTheDocument();
});

it('非活跃用户不能作为目标用户', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([unassignedAccount]);
  api.fetchAdminUsers.mockResolvedValue({
    items: [salesUser, { ...adminUser, id: 'u-8', username: 'locked_ops', displayName: '赵六', status: 'locked' }],
    total: 2,
    page: 0,
    size: 100,
  });
  renderPage(<AdminWhatsAppAccountsPage />);

  await screen.findByText('号码一');
  const dialog = await openRowAction(user, '号码一', /分\s*配/);
  await user.click(within(dialog).getByRole('combobox', { name: '目标用户' }));

  expect(await screen.findByText('李四（sales_a）')).toBeInTheDocument();
  expect(screen.queryByText('赵六（locked_ops）')).not.toBeInTheDocument();
});

it('展示当前管理员自己持有的号码', async () => {
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([]);
  api.fetchWhatsAppAccounts.mockResolvedValue([{
    accountId: 'mine-1',
    mode: 'API_ONLY',
    name: '我的号码',
    remark: null,
    maskedPhone: '+86 138****0009',
    providerStatus: 'ACTIVE',
    verificationStatus: 'VERIFIED',
    templateDomain: 'ENTERPRISE_SHARED',
    recoverableError: null,
  }]);
  renderPage(<AdminWhatsAppAccountsPage />);

  expect(await screen.findByText('我持有的号码')).toBeVisible();
  expect(await screen.findByText('+86 138****0009')).toBeVisible();
  expect(screen.getByText('我的号码')).toBeVisible();
});

it('没有归属自己的号码时给出说明而不是留空', async () => {
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([]);
  renderPage(<AdminWhatsAppAccountsPage />);

  expect(await screen.findByText('我持有的号码')).toBeVisible();
  expect(await screen.findByText('当前没有归属你的可发送号码（已停用或已收回的号码不在此列出）')).toBeVisible();
});

it('没有任何 CAMS 时不显示停用警告', async () => {
  api.fetchAdminCams.mockResolvedValue([]);
  renderPage(<AdminWhatsAppAccountsPage />);

  expect(await screen.findByText('当前 CAMS 暂无同步号码')).toBeInTheDocument();
  expect(screen.queryByText('当前 CAMS 已停用')).not.toBeInTheDocument();
});

it('选中的 CAMS 停用时显示停用警告', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([blockedScope, readyScope]);
  renderPage(<AdminWhatsAppAccountsPage />);

  await waitFor(() => expect(api.fetchAdminScopedWhatsAppAccounts).toHaveBeenCalledWith('cams-1'));
  await user.click(screen.getByRole('combobox', { name: '选择 CAMS' }));
  await pickSelectOption(user, '已停用空间（cust-2）');

  expect(await screen.findByText('当前 CAMS 已停用')).toBeVisible();
});

function apiFailure(code: string) {
  return { response: { data: { code, message: code } } };
}

it('转交失败时展示后端错误码对应的原因，而不是统一提示账号状态已变化', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([assignedAccount]);
  api.transferAdminScopedWhatsAppAccount.mockRejectedValue(apiFailure('WHATSAPP_ACCOUNT_NOT_SENDABLE'));
  renderPage(<AdminWhatsAppAccountsPage />);

  await screen.findByText('号码二');
  const dialog = await openRowAction(user, '号码二', /转\s*交/);
  await user.click(within(dialog).getByRole('combobox', { name: '目标用户' }));
  await pickSelectOption(user, '王五（admin_ops）');
  await user.type(within(dialog).getByLabelText('原因'), '区域调整');
  await user.click(within(dialog).getByRole('button', { name: /转\s*交/ }));

  expect(await screen.findByText('该号码当前不可发送（本地状态已停用），请先在 CAMS 同步恢复后再操作')).toBeVisible();
  expect(screen.queryByText('账号状态已变化，已刷新当前列表，请重试')).not.toBeInTheDocument();
});

it('目标用户已持有号码时展示对应的原因', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([unassignedAccount]);
  api.assignAdminScopedWhatsAppAccount.mockRejectedValue(apiFailure('WHATSAPP_TARGET_ACCOUNT_ALREADY_EXISTS'));
  renderPage(<AdminWhatsAppAccountsPage />);

  await screen.findByText('号码一');
  const dialog = await openRowAction(user, '号码一', /分\s*配/);
  await user.click(within(dialog).getByRole('combobox', { name: '目标用户' }));
  await pickSelectOption(user, '李四（sales_a）');
  await user.type(within(dialog).getByLabelText('原因'), '新入职销售');
  await user.click(within(dialog).getByRole('button', { name: /分\s*配/ }));

  expect(await screen.findByText('目标用户已持有另一个 WhatsApp 号码，请先收回再分配')).toBeVisible();
});

it('只有版本冲突才提示账号状态已变化并刷新账号列表', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([assignedAccount]);
  api.reclaimAdminScopedWhatsAppAccount.mockRejectedValue(apiFailure('WHATSAPP_ASSIGNMENT_CONFLICT'));
  renderPage(<AdminWhatsAppAccountsPage />);

  await screen.findByText('号码二');
  const dialog = await openRowAction(user, '号码二', /收\s*回/);
  await user.type(within(dialog).getByLabelText('原因'), '人员离职');
  await user.click(within(dialog).getByRole('button', { name: /收\s*回/ }));

  expect(await screen.findByText('账号状态已变化，已刷新当前列表，请重试')).toBeVisible();
  await waitFor(() => expect(api.fetchAdminScopedWhatsAppAccounts).toHaveBeenCalledTimes(2));
});

it('未知错误码原样展示，便于排查', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([assignedAccount]);
  api.transferAdminScopedWhatsAppAccount.mockRejectedValue(apiFailure('WHATSAPP_SOMETHING_NEW'));
  renderPage(<AdminWhatsAppAccountsPage />);

  await screen.findByText('号码二');
  const dialog = await openRowAction(user, '号码二', /转\s*交/);
  await user.click(within(dialog).getByRole('combobox', { name: '目标用户' }));
  await pickSelectOption(user, '王五（admin_ops）');
  await user.type(within(dialog).getByLabelText('原因'), '区域调整');
  await user.click(within(dialog).getByRole('button', { name: /转\s*交/ }));

  expect(await screen.findByText('转交失败（WHATSAPP_SOMETHING_NEW）')).toBeVisible();
});

it('同步失败时展示后端错误码对应的原因', async () => {
  const user = userEvent.setup();
  api.fetchAdminCams.mockResolvedValue([readyScope]);
  api.fetchAdminScopedWhatsAppAccounts.mockResolvedValue([]);
  api.syncAdminCams.mockRejectedValue(apiFailure('WHATSAPP_CAMS_SYNC_UNAVAILABLE'));
  renderPage(<AdminWhatsAppAccountsPage />);

  await waitFor(() => expect(api.fetchAdminScopedWhatsAppAccounts).toHaveBeenCalledWith('cams-1'));
  await user.click(screen.getByRole('button', { name: /同步当前 CAMS/ }));

  expect(await screen.findByText('当前 CAMS 不可用或未就绪，无法同步')).toBeVisible();
});
