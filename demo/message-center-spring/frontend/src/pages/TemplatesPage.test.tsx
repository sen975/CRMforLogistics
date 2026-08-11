import { App as AntApp, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import TemplatesPage from './TemplatesPage';
import type { TemplateAdmin, TemplateOperation } from '../api/types';

const api = vi.hoisted(() => ({
  fetchTemplates: vi.fn(),
  fetchChannelAccounts: vi.fn(),
  fetchAdminTemplates: vi.fn(),
  fetchAdminTemplate: vi.fn(),
  createAdminTemplate: vi.fn(),
  updateAdminTemplate: vi.fn(),
  setAdminTemplateSendPermission: vi.fn(),
  deleteAdminTemplate: vi.fn(),
  syncAdminTemplates: vi.fn(),
  uploadTemplateMedia: vi.fn(),
  fetchTemplateOperations: vi.fn(),
}));

vi.mock('../api/endpoints', () => api);

const account = {
  id: 'account-1',
  channelType: 'chatapp',
  name: 'WhatsApp 主账号',
  accountIdentifier: 'wa-primary',
  authStatus: 'active',
  syncStatus: 'idle',
  lastSyncedAt: '2026-08-11T08:00:00Z',
  createdAt: '2026-08-01T08:00:00Z',
};

const template: TemplateAdmin = {
  id: 'template-1',
  accountId: account.id,
  templateCode: 'welcome',
  name: '欢迎通知',
  language: 'zh_CN',
  category: 'UTILITY',
  reviewStatus: 'REJECTED',
  providerAuditStatus: 'fail',
  rejectionReason: 'BODY_NOT_ALLOWED',
  allowSend: true,
  components: [
    { type: 'BODY', headerFormat: null, text: '你好 {{customer}}', mediaAssetId: null, buttons: [] },
  ],
  examples: { customer: ['张三'] },
  messageSendTtlSeconds: 3600,
  qualityScore: 'GREEN',
  providerUpdatedAt: '2026-08-11T08:00:00Z',
  lastSyncedAt: '2026-08-11T08:05:00Z',
  deletedAt: null,
};

const succeededOperation: TemplateOperation = {
  operationId: 'operation-1',
  operationType: 'CREATE',
  operationStatus: 'SUCCEEDED',
  templateCode: 'welcome',
  language: 'zh_CN',
  errorCode: null,
  errorMessage: null,
  traceId: 'trace-1',
  actorUserId: 'user-1',
  startedAt: '2026-08-11T08:00:00Z',
  completedAt: '2026-08-11T08:00:01Z',
};

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <AntApp>
          <TemplatesPage />
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

async function choose(label: string, option: string) {
  fireEvent.mouseDown(screen.getByRole('combobox', { name: label }));
  fireEvent.click(await screen.findByText(option, { selector: '.ant-select-item-option-content' }));
}

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchTemplates.mockResolvedValue([]);
  api.fetchChannelAccounts.mockResolvedValue([account]);
  api.fetchAdminTemplates.mockResolvedValue({ items: [template], total: 1, page: 1, size: 20 });
  api.fetchAdminTemplate.mockResolvedValue(template);
  api.fetchTemplateOperations.mockResolvedValue([succeededOperation]);
  api.createAdminTemplate.mockResolvedValue(succeededOperation);
  api.updateAdminTemplate.mockResolvedValue({ ...succeededOperation, operationType: 'MODIFY' });
  api.setAdminTemplateSendPermission.mockResolvedValue({
    ...succeededOperation,
    operationType: 'SET_SEND_PERMISSION',
  });
  api.deleteAdminTemplate.mockResolvedValue({ ...succeededOperation, operationType: 'DELETE' });
  api.syncAdminTemplates.mockResolvedValue({ pages: 1, fetched: 1, changed: 0, complete: true });
});

describe('TemplatesPage', () => {
  it('sends status, category, language, send and deleted filters to the account-scoped list', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('欢迎通知');

    await user.type(screen.getByPlaceholderText('搜索名称或代码'), 'wel');
    await choose('审核状态', '已通过');
    await choose('模板类别', '营销');
    await choose('语言', '简体中文');
    await choose('发送状态', '已启用');
    await choose('删除状态', '已删除');

    await waitFor(() => {
      expect(api.fetchAdminTemplates).toHaveBeenLastCalledWith('account-1', expect.objectContaining({
        search: 'wel',
        status: 'APPROVED',
        category: 'MARKETING',
        language: 'zh_CN',
        allowSend: true,
        deleted: true,
      }));
    });
  });

  it('creates a structured BODY template with variable examples', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '新建模板' }));
    await user.type(screen.getByLabelText('模板名称'), 'order_ready');
    await user.type(screen.getByRole('textbox', { name: '语言' }), 'zh_CN');
    fireEvent.change(screen.getByLabelText('正文'), { target: { value: '订单 {{customer}} 已就绪' } });
    await user.type(await screen.findByLabelText('customer 示例'), 'Ada');
    await user.click(screen.getByRole('button', { name: '提交创建' }));

    await waitFor(() => expect(api.createAdminTemplate).toHaveBeenCalledTimes(1));
    expect(api.createAdminTemplate).toHaveBeenCalledWith('account-1', expect.objectContaining({
      name: 'order_ready',
      language: 'zh_CN',
      category: 'UTILITY',
      components: expect.arrayContaining([
        expect.objectContaining({ type: 'BODY', text: '订单 {{customer}} 已就绪' }),
      ]),
      examples: { customer: ['Ada'] },
      clientRequestId: expect.any(String),
    }));
  });

  it('shows an indeterminate upload state and then the returned internal asset id', async () => {
    const user = userEvent.setup();
    let finishUpload!: (value: unknown) => void;
    api.uploadTemplateMedia.mockImplementation(() => new Promise((resolve) => { finishUpload = resolve; }));
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '新建模板' }));
    await choose('Header 类型', '图片');
    const file = new File(['image'], 'header.png', { type: 'image/png' });
    await user.upload(screen.getByLabelText('Header 素材'), file);
    expect(await screen.findByText('正在上传素材')).toBeInTheDocument();

    finishUpload({
      id: 'asset-1',
      format: 'IMAGE',
      contentType: 'image/png',
      sizeBytes: 5,
      sha256: 'abc',
      providerUrl: 'https://provider.invalid/header.png',
      assetStatus: 'UPLOADED',
    });
    expect(await screen.findByText('素材 ID：asset-1')).toBeInTheDocument();
  });

  it('edits a template and keeps the resulting pending-review state visible', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '编辑 welcome' }));
    const body = screen.getByLabelText('正文');
    await user.clear(body);
    await user.type(body, '更新后的正文');
    await user.click(screen.getByRole('button', { name: '提交修改' }));

    await waitFor(() => expect(api.updateAdminTemplate).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('已提交修改，模板将重新进入审核')).toBeInTheDocument();
  });

  it('requires confirmation before pausing and deleting a template', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '暂停发送 welcome' }));
    expect(await screen.findByText('确认暂停该模板发送？')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /确\s*认/ }));
    await waitFor(() => expect(api.setAdminTemplateSendPermission).toHaveBeenCalledWith(
      'account-1', 'welcome', 'zh_CN', false, expect.any(String),
    ));

    await user.click(screen.getByRole('button', { name: '删除 welcome' }));
    expect(await screen.findByText('删除后模板将不再用于发送，确认删除？')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /确\s*认\s*删\s*除/ }));
    await waitFor(() => expect(api.deleteAdminTemplate).toHaveBeenCalledWith(
      'account-1', 'welcome', 'zh_CN', expect.any(String),
    ));
  });

  it('shows rejection details and operation history in the detail drawer', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '查看 welcome' }));
    const drawer = await screen.findByRole('dialog', { name: '模板详情' });
    expect(within(drawer).getByText('BODY_NOT_ALLOWED')).toBeInTheDocument();
    expect(within(drawer).getByText('操作记录')).toBeInTheDocument();
    expect(await within(drawer).findByText('CREATE')).toBeInTheDocument();
  });

  it('renders a persistent permission error for a 403 list response', async () => {
    api.fetchAdminTemplates.mockRejectedValue({
      response: { status: 403, data: { code: 'FORBIDDEN', message: 'Forbidden', traceId: 'trace-403' } },
    });
    renderPage();

    expect(await screen.findByText('你没有模板管理权限')).toBeInTheDocument();
    expect(screen.getByText('追踪 ID：trace-403')).toBeInTheDocument();
  });

  it('keeps upload failures and submission-unknown outcomes visible', async () => {
    const user = userEvent.setup();
    api.uploadTemplateMedia.mockRejectedValue({
      response: {
        data: {
          code: 'TEMPLATE_MEDIA_UPLOAD_FAILED',
          message: '素材上传失败',
          traceId: 'trace-upload',
        },
      },
    });
    api.createAdminTemplate.mockResolvedValue({
      ...succeededOperation,
      operationStatus: 'SUBMISSION_UNKNOWN',
      errorCode: 'PROVIDER_TIMEOUT',
      errorMessage: '提交结果未知',
    });
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '新建模板' }));
    await choose('Header 类型', '图片');
    fireEvent.change(screen.getByLabelText('Header 素材'), {
      target: { files: [new File(['image'], 'header.png', { type: 'image/png' })] },
    });
    expect(await screen.findByText('素材上传失败')).toBeInTheDocument();

    await user.type(screen.getByLabelText('模板名称'), 'timeout_template');
    await user.type(screen.getByRole('textbox', { name: '语言' }), 'zh_CN');
    await user.type(screen.getByLabelText('正文'), '消息正文');
    await user.click(screen.getByRole('button', { name: '提交创建' }));

    expect(await screen.findByText('提交结果未知，请通过操作记录和同步结果确认最终状态')).toBeInTheDocument();
  });
});
