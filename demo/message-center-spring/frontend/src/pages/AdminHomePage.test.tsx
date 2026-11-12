import '@testing-library/jest-dom/vitest';
import { App as AntApp, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import type { AdminWhatsAppOverview, AdminWhatsAppScopeOverview } from '../api/types';
import AdminHomePage from './AdminHomePage';

// fetchAdminCams / fetchTemplateChangeRequestsForReview are deliberately left unstubbed: any call
// returns undefined and rejects, which is what makes the "no client-side inference" test bite.
const api = vi.hoisted(() => ({
  fetchAdminWhatsAppOverview: vi.fn(),
  fetchAdminCams: vi.fn(),
  fetchTemplateChangeRequestsForReview: vi.fn(),
}));

vi.mock('../api/endpoints', () => api);

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ConfigProvider>
          <AntApp>
            <AdminHomePage />
          </AntApp>
        </ConfigProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function scope(overrides: Partial<AdminWhatsAppScopeOverview> & { scopeId: string }): AdminWhatsAppScopeOverview {
  return {
    displayName: '默认空间',
    custSpaceId: 'cust-default',
    status: 'READY',
    usableAccountCount: 0,
    pendingApprovalCount: 0,
    lastTestedAt: null,
    lastTestStatus: null,
    lastTestErrorCode: null,
    lastSyncedAt: null,
    lastSyncStatus: null,
    lastSyncErrorCode: null,
    ...overrides,
  };
}

function overview(scopes: AdminWhatsAppScopeOverview[], totalPendingApprovalCount: number): AdminWhatsAppOverview {
  return { scopes, totalPendingApprovalCount };
}

function statisticCard(title: string): HTMLElement {
  return screen.getByText(title).closest('.ant-statistic') as HTMLElement;
}

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchAdminWhatsAppOverview.mockResolvedValue(overview([], 0));
});

it('按服务端返回的每个 CAMS 数字渲染平台状态', async () => {
  api.fetchAdminWhatsAppOverview.mockResolvedValue(overview([
    scope({ scopeId: 's-1', displayName: '华东 CAMS', custSpaceId: 'cust-space-1', usableAccountCount: 41, pendingApprovalCount: 7, lastSyncedAt: '2026-09-15T03:00:00Z' }),
    scope({ scopeId: 's-2', displayName: '华南 CAMS', custSpaceId: 'cust-space-2', usableAccountCount: 23, pendingApprovalCount: 5, lastSyncedAt: '2026-09-14T22:00:00Z' }),
    scope({ scopeId: 's-3', displayName: '华北 CAMS', custSpaceId: 'cust-space-3', usableAccountCount: 12, pendingApprovalCount: 1 }),
  ], 12));
  renderPage();

  expect(await screen.findByText('华东 CAMS')).toBeVisible();
  expect(screen.getByText('华南 CAMS')).toBeVisible();
  expect(screen.getByText('可用号码 41')).toBeVisible();
  expect(screen.getByText('可用号码 23')).toBeVisible();
  expect(screen.getByText('待审批 7')).toBeVisible();
  expect(screen.getByText('待审批 5')).toBeVisible();
  const expectedSync = `最近同步 ${new Date('2026-09-15T03:00:00Z').toLocaleString('zh-CN')}`;
  expect(screen.getByText(expectedSync)).toBeVisible();
  // s-3 的 lastSyncedAt 为 null（见 scope() 默认值），此处钉住「尚未同步」分支按字段选择，
  // 而不是无条件渲染某一种字符串：s-1 断言 最近同步、s-3 断言 尚未同步，二者同时可见。
  expect(screen.getByText('尚未同步')).toBeVisible();
});

it('待审批统计取自服务端总数而非审批队列的行数', async () => {
  api.fetchAdminWhatsAppOverview.mockResolvedValue(overview([
    scope({ scopeId: 's-1', displayName: '华东 CAMS', usableAccountCount: 41, pendingApprovalCount: 4 }),
    scope({ scopeId: 's-2', displayName: '华南 CAMS', usableAccountCount: 23, pendingApprovalCount: 9 }),
  ], 31));
  renderPage();

  expect(await screen.findByText('华东 CAMS')).toBeVisible();
  // 31 matches no scope's pendingApprovalCount (4 and 9), so a page that sums the scope
  // projections or reads the review queue total fails here.
  expect(within(statisticCard('待审批')).getByText('31')).toBeVisible();
});

it('不调用 CAMS 列表与模板审批队列接口', async () => {
  api.fetchAdminWhatsAppOverview.mockResolvedValue(overview([
    scope({ scopeId: 's-1', usableAccountCount: 41, pendingApprovalCount: 7 }),
  ], 7));
  renderPage();

  expect(await screen.findByText('默认空间')).toBeVisible();
  expect(api.fetchAdminWhatsAppOverview).toHaveBeenCalledTimes(1);
  expect(api.fetchAdminCams).not.toHaveBeenCalled();
  expect(api.fetchTemplateChangeRequestsForReview).not.toHaveBeenCalled();
});

it('失败提示只出现在失败的范围上', async () => {
  api.fetchAdminWhatsAppOverview.mockResolvedValue(overview([
    scope({ scopeId: 's-1', displayName: '同步失败空间', usableAccountCount: 41, pendingApprovalCount: 7, lastSyncedAt: '2026-09-15T03:00:00Z', lastSyncStatus: 'FAILED', lastSyncErrorCode: 'SYNC_TIMEOUT_500' }),
    scope({ scopeId: 's-2', displayName: '测试失败空间', usableAccountCount: 23, pendingApprovalCount: 5, lastTestedAt: '2026-09-15T02:00:00Z', lastTestStatus: 'FAILED', lastTestErrorCode: 'AUTH_FAILED_401' }),
    scope({ scopeId: 's-3', displayName: '健康空间', usableAccountCount: 37, pendingApprovalCount: 3, lastSyncedAt: '2026-09-15T03:00:00Z', lastSyncStatus: 'SUCCESS', lastTestStatus: 'SUCCESS' }),
  ], 15));
  renderPage();

  expect(await screen.findByText('同步失败空间')).toBeVisible();
  expect(screen.getByText('同步失败：SYNC_TIMEOUT_500')).toBeVisible();
  expect(screen.getByText('连接测试失败：AUTH_FAILED_401')).toBeVisible();
  expect(document.querySelectorAll('.ant-tag-red')).toHaveLength(2);
  // 空状态测试里 可用平台 / 需要关注 都是 0，硬编码 0 也能过；这里三个 scope 全部 status=READY、
  // 两个带 FAILED 状态字段，把两个聚合钉在非零值上。勿因与空状态测试重复而删除。
  expect(within(statisticCard('可用平台')).getByText('3')).toBeVisible();
  expect(within(statisticCard('需要关注')).getByText('2')).toBeVisible();
});

it('总览接口失败时展示错误提示', async () => {
  api.fetchAdminWhatsAppOverview.mockRejectedValue(new Error('boom'));
  renderPage();

  expect(await screen.findByText('管理员数据加载失败')).toBeVisible();
});

it('没有 CAMS 配置时展示空状态且统计为零', async () => {
  api.fetchAdminWhatsAppOverview.mockResolvedValue(overview([], 0));
  renderPage();

  expect(await screen.findByText('暂无 CAMS 配置')).toBeVisible();
  expect(within(statisticCard('CAMS 平台')).getByText('0')).toBeVisible();
  expect(within(statisticCard('可用平台')).getByText('0')).toBeVisible();
  expect(within(statisticCard('待审批')).getByText('0')).toBeVisible();
  expect(within(statisticCard('需要关注')).getByText('0')).toBeVisible();
});
