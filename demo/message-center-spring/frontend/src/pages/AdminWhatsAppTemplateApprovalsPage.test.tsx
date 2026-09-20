import '@testing-library/jest-dom/vitest';
import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import type { TemplateChangeRequestView } from '../api/types';
import AdminWhatsAppTemplateApprovalsPage from './AdminWhatsAppTemplateApprovalsPage';

const api = vi.hoisted(() => ({ fetchTemplateChangeRequestsForReview: vi.fn(), approveTemplateChangeRequest: vi.fn(), rejectTemplateChangeRequest: vi.fn(), retryTemplateChangeRequest: vi.fn() }));
vi.mock('../api/endpoints', () => api);

function request(overrides: { id: string; templateDisplayName: string; status: TemplateChangeRequestView['status'] } & Partial<TemplateChangeRequestView>): TemplateChangeRequestView {
  return { templateId: 'template-1', baseVersion: 3, changeType: 'MODIFY', diffs: [], requestedByDisplayName: '王五', reviewedByDisplayName: null, reviewReason: null, executionErrorCode: null, executionErrorMessage: null, providerRequestId: null, createdAt: '2026-01-01T00:00:00Z', reviewedAt: null, executionCompletedAt: null, providerScopeId: 'cams-1', providerScopeName: '小森', providerScopeExternalId: 'cams-9jvb6o87e6m8', providerScopeType: 'ENTERPRISE_API', ...overrides };
}

const pending = request({ id: 'r-1', templateDisplayName: '发货提醒', status: 'PENDING_APPROVAL', requestedByDisplayName: '李四' });
const succeeded = request({ id: 'r-2', templateDisplayName: '到货通知', status: 'SUCCEEDED' });
const failed = request({ id: 'r-3', templateDisplayName: '账单提醒', status: 'EXECUTION_FAILED', executionErrorMessage: '服务端拒绝' });

// antd inserts a space between the two characters of a two-character CJK button label.
const approveButton = /^批\s*准$/;
const rejectButton = /^拒\s*绝$/;
const retryButton = /^重\s*试$/;

function page(items: TemplateChangeRequestView[], total = items.length) { return { items, total, page: 1, size: 20 }; }

function renderPage() { const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } }); return render(<QueryClientProvider client={client}><ConfigProvider locale={zhCN}><AntApp><AdminWhatsAppTemplateApprovalsPage /></AntApp></ConfigProvider></QueryClientProvider>); }

function itemOf(name: string): HTMLElement {
  const item = screen.getByText(name).closest('.ant-list-item');
  if (!item) throw new Error(`no queue item for ${name}`);
  return item as HTMLElement;
}

// With virtual scrolling rc-select exposes only hidden aria nodes as role="option";
// the visible dropdown label is the element that actually carries the click handler.
async function pickSelectOption(user: ReturnType<typeof userEvent.setup>, label: string) { await user.click(await screen.findByText(label)); }

type ReviewFilters = { status?: string | null; search?: string };

const allRequests = [pending, succeeded, failed];

/** Stands in for the server queue: it filters, and its total describes the filtered set. */
function serverQueue(filters: ReviewFilters = {}) {
  const items = allRequests.filter((item) => (!filters.status || item.status === filters.status)
    && (!filters.search || `${item.templateDisplayName} ${item.requestedByDisplayName}`.toLowerCase().includes(filters.search.toLowerCase())));
  return page(items, items.length);
}

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchTemplateChangeRequestsForReview.mockImplementation((_pageNumber: number, _size: number, filters: ReviewFilters = {}) => Promise.resolve(serverQueue(filters)));
  api.approveTemplateChangeRequest.mockResolvedValue({ mode: 'DIRECT', request: null, operation: null });
  api.rejectTemplateChangeRequest.mockResolvedValue({ ...pending, status: 'REJECTED' });
  api.retryTemplateChangeRequest.mockResolvedValue({ mode: 'DIRECT', request: null, operation: null });
});

it('队列渲染模板名、申请人和状态标签', async () => {
  renderPage();

  expect(await screen.findByText('发货提醒')).toBeVisible();
  expect(screen.getByRole('heading', { name: 'WhatsApp 模板审批' })).toBeVisible();
  expect(within(itemOf('发货提醒')).getByText('申请人：李四')).toBeVisible();
  expect(within(itemOf('发货提醒')).getByText('等待审批')).toBeVisible();
  expect(within(itemOf('账单提醒')).getByText('服务端拒绝')).toBeVisible();
});

it('PENDING_APPROVAL 的批准按钮带上客户端请求号调用 approve 接口', async () => {
  const user = userEvent.setup();
  renderPage();

  await screen.findByText('发货提醒');
  await user.click(within(itemOf('发货提醒')).getByRole('button', { name: approveButton }));

  await waitFor(() => expect(api.approveTemplateChangeRequest).toHaveBeenCalledTimes(1));
  expect(api.approveTemplateChangeRequest).toHaveBeenCalledWith('r-1', expect.any(String));
  expect(api.approveTemplateChangeRequest.mock.calls[0][1].length).toBeGreaterThan(0);
});

it('非 PENDING_APPROVAL 的批准按钮禁用且点击不调用接口', async () => {
  const user = userEvent.setup();
  renderPage();

  await screen.findByText('到货通知');
  const button = within(itemOf('到货通知')).getByRole('button', { name: approveButton });
  expect(button).toBeDisabled();

  await user.click(button);
  expect(api.approveTemplateChangeRequest).not.toHaveBeenCalled();
});

it('拒绝把输入的理由传给 reject 接口', async () => {
  const user = userEvent.setup();
  renderPage();

  await screen.findByText('发货提醒');
  const item = itemOf('发货提醒');
  await user.type(within(item).getByLabelText('拒绝理由 发货提醒'), '信息与合同不符');
  await user.click(within(item).getByRole('button', { name: rejectButton }));

  await waitFor(() => expect(api.rejectTemplateChangeRequest).toHaveBeenCalledWith('r-1', '信息与合同不符'));
});

it('拒绝理由为空时提示且不调用接口', async () => {
  const user = userEvent.setup();
  renderPage();

  await screen.findByText('发货提醒');
  await user.click(within(itemOf('发货提醒')).getByRole('button', { name: rejectButton }));

  expect(await screen.findByText('拒绝理由不能为空')).toBeVisible();
  expect(api.rejectTemplateChangeRequest).not.toHaveBeenCalled();
});

it('拒绝理由只有空白时提示且不调用接口', async () => {
  const user = userEvent.setup();
  renderPage();

  await screen.findByText('发货提醒');
  const item = itemOf('发货提醒');
  await user.type(within(item).getByLabelText('拒绝理由 发货提醒'), '   ');
  await user.click(within(item).getByRole('button', { name: rejectButton }));

  expect(await screen.findByText('拒绝理由不能为空')).toBeVisible();
  expect(api.rejectTemplateChangeRequest).not.toHaveBeenCalled();
});

it('重试只在 EXECUTION_FAILED 出现并调用 retry 接口', async () => {
  const user = userEvent.setup();
  renderPage();

  await screen.findByText('账单提醒');
  expect(within(itemOf('发货提醒')).queryByRole('button', { name: retryButton })).not.toBeInTheDocument();
  expect(within(itemOf('到货通知')).queryByRole('button', { name: retryButton })).not.toBeInTheDocument();

  await user.click(within(itemOf('账单提醒')).getByRole('button', { name: retryButton }));

  await waitFor(() => expect(api.retryTemplateChangeRequest).toHaveBeenCalledTimes(1));
  expect(api.retryTemplateChangeRequest).toHaveBeenCalledWith('r-3', expect.any(String));
});

it('审批状态作为服务端查询参数发出，队列按服务端结果渲染', async () => {
  const user = userEvent.setup();
  renderPage();

  await screen.findByText('发货提醒');
  await user.click(screen.getByRole('combobox', { name: '审批状态' }));
  await pickSelectOption(user, '已完成');

  await waitFor(() => expect(api.fetchTemplateChangeRequestsForReview).toHaveBeenLastCalledWith(1, 20, { status: 'SUCCEEDED', search: '' }));
  expect(screen.getByText('到货通知')).toBeVisible();
  expect(screen.queryByText('发货提醒')).not.toBeInTheDocument();
  expect(screen.queryByText('账单提醒')).not.toBeInTheDocument();
});

it('搜索作为服务端查询参数发出，返回行不再被客户端二次过滤', async () => {
  const user = userEvent.setup();
  // The stub answers a search with a row whose name does not contain the term: only a page that trusts
  // the server can render it, the previous client-side filter would have hidden it.
  api.fetchTemplateChangeRequestsForReview.mockImplementation((_pageNumber: number, _size: number, filters: ReviewFilters = {}) =>
    Promise.resolve(filters.search ? page([succeeded], 1) : page(allRequests, allRequests.length)));
  renderPage();

  await screen.findByText('发货提醒');
  await user.type(screen.getByLabelText('搜索申请'), 'zzz');

  await waitFor(() => expect(api.fetchTemplateChangeRequestsForReview).toHaveBeenLastCalledWith(1, 20, { status: undefined, search: 'zzz' }));
  expect(await screen.findByText('到货通知')).toBeVisible();
  expect(screen.queryByText('发货提醒')).not.toBeInTheDocument();
});

it('分页总数取服务端筛选后的 total', async () => {
  const user = userEvent.setup();
  // Unfiltered: 47 rows (3 pages). Filtered: 21 rows (2 pages) — the pager must follow the filtered total.
  api.fetchTemplateChangeRequestsForReview.mockImplementation((_pageNumber: number, _size: number, filters: ReviewFilters = {}) =>
    Promise.resolve(filters.status === 'SUCCEEDED' ? page([succeeded], 21) : page(allRequests, 47)));
  renderPage();

  await screen.findByText('发货提醒');
  expect(await screen.findByRole('listitem', { name: '3' })).toBeVisible();

  await user.click(screen.getByRole('combobox', { name: '审批状态' }));
  await pickSelectOption(user, '已完成');

  await waitFor(() => expect(api.fetchTemplateChangeRequestsForReview).toHaveBeenLastCalledWith(1, 20, { status: 'SUCCEEDED', search: '' }));
  await waitFor(() => expect(screen.queryByRole('listitem', { name: '3' })).not.toBeInTheDocument());
  expect(screen.getByRole('listitem', { name: '2' })).toBeVisible();
});

it('查询失败时显示加载失败告警', async () => {
  api.fetchTemplateChangeRequestsForReview.mockRejectedValue(new Error('boom'));
  renderPage();

  expect(await screen.findByText('审批队列加载失败')).toBeVisible();
});

it('总数超过一页时渲染分页并在翻页后按新页码重查', async () => {
  const user = userEvent.setup();
  const secondPage = request({ id: 'r-9', templateDisplayName: '第二页模板', status: 'PENDING_APPROVAL', requestedByDisplayName: '赵六' });
  api.fetchTemplateChangeRequestsForReview.mockImplementation((pageNumber: number) => Promise.resolve({ items: pageNumber === 2 ? [secondPage] : [pending], total: 25, page: pageNumber, size: 20 }));
  renderPage();

  await screen.findByText('发货提醒');
  expect(api.fetchTemplateChangeRequestsForReview).toHaveBeenCalledWith(1, 20, { status: undefined, search: '' });
  expect(screen.getByRole('listitem', { name: '2' })).toBeVisible();

  await user.click(screen.getByRole('listitem', { name: '2' }));

  await waitFor(() => expect(api.fetchTemplateChangeRequestsForReview).toHaveBeenLastCalledWith(2, 20, { status: undefined, search: '' }));
  expect(await screen.findByText('第二页模板')).toBeVisible();
  expect(screen.queryByText('发货提醒')).not.toBeInTheDocument();
});
