import { App as AntApp, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '@testing-library/jest-dom/vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import TemplatesPage from './TemplatesPage';
import TemplateEditorDrawer from '../components/templates/TemplateEditorDrawer';
import type { TemplateAdmin, TemplateOperation } from '../api/types';

const api = vi.hoisted(() => ({
  fetchTemplates: vi.fn(),
  fetchChannelAccounts: vi.fn(),
  fetchAdminTemplates: vi.fn(),
  fetchAdminTemplate: vi.fn(),
  createAdminTemplate: vi.fn(),
  updateAdminTemplate: vi.fn(),
  updateAdminTemplateRemark: vi.fn(),
  setAdminTemplateSendPermission: vi.fn(),
  deleteAdminTemplate: vi.fn(),
  syncAdminTemplates: vi.fn(),
  uploadTemplateMedia: vi.fn(),
  fetchTemplateMediaUpload: vi.fn(),
  fetchTemplateOperations: vi.fn(),
  fetchPublicTemplates: vi.fn(),
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
  remark: null,
  displayName: '欢迎通知',
  language: 'zh_CN',
  category: 'UTILITY',
  reviewStatus: 'REJECTED',
  providerAuditStatus: 'fail',
  rejectionReason: 'BODY_NOT_ALLOWED',
  allowSend: true,
  desiredAllowSend: true,
  permissionSyncStatus: 'IDLE',
  permissionSyncError: null,
  components: [
    { type: 'BODY', headerFormat: null, text: '你好 $(customer)', mediaAssetId: null, buttons: [] },
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
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <AntApp>
          <TemplatesPage />
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>,
  );
  return { ...rendered, queryClient };
}

async function choose(label: string, option: string) {
  const controls = screen.getAllByRole('combobox', { name: label });
  fireEvent.mouseDown(controls[controls.length - 1]);
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
  api.updateAdminTemplateRemark.mockResolvedValue(template);
  api.setAdminTemplateSendPermission.mockResolvedValue({
    ...succeededOperation,
    operationType: 'SET_SEND_PERMISSION',
  });
  api.deleteAdminTemplate.mockResolvedValue({ ...succeededOperation, operationType: 'DELETE' });
  api.syncAdminTemplates.mockResolvedValue({ pages: 1, fetched: 1, changed: 0, complete: true });
  api.fetchPublicTemplates.mockResolvedValue({ items: [], total: 0, page: 1, size: 20 });
});

afterEach(() => vi.useRealTimers());

describe('TemplatesPage', () => {
  it('renders my templates as preview cards and opens the wide detail workbench', async () => {
    const user = userEvent.setup();
    renderPage();

    const card = await screen.findByRole('button', { name: '查看我的模板 welcome' });
    expect(card).toHaveTextContent('欢迎通知');
    expect(card).toHaveTextContent('已拒绝');
    expect(card).toHaveTextContent('已启用');

    await user.click(card);

    const detail = await screen.findByRole('dialog', { name: '模板详情' });
    expect(within(detail).getByRole('heading', { name: '欢迎通知' })).toBeInTheDocument();
    expect(within(detail).getByLabelText('模板消息预览')).toHaveTextContent('你好 customer');
    expect(within(detail).getByText('BODY_NOT_ALLOWED')).toBeInTheDocument();

    await user.click(within(detail).getByText('示例', { exact: true }));
    expect(within(detail).getByLabelText('模板消息预览')).toHaveTextContent('你好 张三');
  });

  it('shows pending permission state and prevents duplicate toggles', async () => {
    api.fetchAdminTemplates.mockResolvedValue({
      items: [{
        ...template,
        reviewStatus: 'APPROVED',
        allowSend: false,
        desiredAllowSend: true,
        permissionSyncStatus: 'PENDING',
      }],
      total: 1,
      page: 1,
      size: 20,
    });
    renderPage();

    expect(await screen.findByText('启用同步中')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '暂停发送 welcome' })).toBeDisabled();
  });

  it('shows permission failure and retries the desired state', async () => {
    const user = userEvent.setup();
    api.fetchAdminTemplates.mockResolvedValue({
      items: [{
        ...template,
        reviewStatus: 'APPROVED',
        allowSend: false,
        desiredAllowSend: true,
        permissionSyncStatus: 'FAILED',
        permissionSyncError: 'CAMS 暂时不可用',
      }],
      total: 1,
      page: 1,
      size: 20,
    });
    renderPage();

    expect(await screen.findByText('启用失败')).toBeInTheDocument();
    expect(screen.getByText('CAMS 暂时不可用')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '暂停发送 welcome' }));
    await user.click(screen.getByRole('button', { name: /确\s*认/ }));
    await waitFor(() => expect(api.setAdminTemplateSendPermission)
      .toHaveBeenCalledWith('account-1', 'welcome', 'zh_CN', false, expect.any(String)));
  });

  it('does not allow an unapproved disabled template to be enabled', async () => {
    api.fetchAdminTemplates.mockResolvedValue({
      items: [{
        ...template,
        allowSend: false,
        desiredAllowSend: false,
        permissionSyncStatus: 'IDLE',
      }],
      total: 1,
      page: 1,
      size: 20,
    });
    renderPage();

    expect(await screen.findByText('已暂停')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '恢复发送 welcome' })).toBeDisabled();
  });

  it('creates a custom template from a public draft through the existing create mutation', async () => {
    const user = userEvent.setup();
    api.fetchPublicTemplates.mockResolvedValue({
      items: [{
        code: 'public-1', name: 'account_creation_confirmation_3', language: 'zh_CN', category: 'UTILITY', industries: [], usecase: null, topic: null,
        content: {
          templateName: 'account_creation_confirmation_3', sceneTemplateName: 'account_creation_confirmation_3', externalTemplateCode: null, languageCode: 'zh_CN', category: 'UTILITY',
          pages: [{ name: 'BODY', text: '$(text)，您好：您的新帐号已成功创建。', buttons: [{ name: '查看详情', type: 'visitWebsite', url: 'https://example.com' }] }],
          variables: [{ code: 'text', name: '姓名', example: 'John', format: 'TEXT' }],
        },
      }], total: 1, page: 1, size: 20,
    });
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('tab', { name: '公共模板库' }));
    await user.click(await screen.findByRole('button', { name: '查看公共模板 public-1' }));
    const detail = await screen.findByRole('dialog', { name: 'account_creation_confirmation_3' });
    await user.click(within(detail).getByRole('button', { name: '基于此模板创建' }));
    expect(await screen.findByLabelText('模板名称')).toHaveValue('account_creation_confirmation_3_custom');

    await user.click(screen.getByRole('button', { name: '提交创建' }));

    await waitFor(() => expect(api.createAdminTemplate).toHaveBeenCalledWith('account-1', expect.objectContaining({
      name: 'account_creation_confirmation_3_custom',
      language: 'zh_CN',
      category: 'UTILITY',
      components: expect.arrayContaining([
        expect.objectContaining({ type: 'BODY', text: '$(text)，您好：您的新帐号已成功创建。' }),
        expect.objectContaining({ type: 'BUTTONS', buttons: [expect.objectContaining({ type: 'URL', text: '查看详情', url: 'https://example.com' })] }),
      ]),
      examples: { text: ['John'] },
    })));
  });

  it('ignores a stale create success after the active account changes from A to B to A', async () => {
    const accountB = { ...account, id: 'account-2', name: 'WhatsApp 备用账号' };
    let resolveOldCreate!: (value: TemplateOperation) => void;
    api.createAdminTemplate.mockImplementationOnce(() => new Promise((resolve) => { resolveOldCreate = resolve; }));
    const user = userEvent.setup();
    const { queryClient } = renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '新建模板' }));
    await user.type(screen.getByLabelText('模板名称'), 'old_account_draft');
    await choose('语言', '简体中文');
    await user.type(screen.getByLabelText('正文'), '旧账号请求');
    await user.click(screen.getByRole('button', { name: '提交创建' }));
    await waitFor(() => expect(api.createAdminTemplate).toHaveBeenCalledWith('account-1', expect.objectContaining({ name: 'old_account_draft' })));

    await act(async () => { queryClient.setQueryData(['channel-accounts'], [accountB]); });
    await waitFor(() => expect(api.fetchAdminTemplates).toHaveBeenLastCalledWith('account-2', expect.any(Object)));
    await act(async () => { queryClient.setQueryData(['channel-accounts'], [account]); });
    await waitFor(() => expect(api.fetchAdminTemplates).toHaveBeenLastCalledWith('account-1', expect.any(Object)));

    await user.click(screen.getByRole('button', { name: '新建模板' }));
    await user.type(screen.getByLabelText('模板名称'), 'current_account_draft');
    await choose('语言', '简体中文');
    await user.type(screen.getByLabelText('正文'), '当前账号请求');
    expect(screen.getByLabelText('模板名称')).toHaveValue('current_account_draft');

    await act(async () => { resolveOldCreate(succeededOperation); });

    expect(screen.getByLabelText('模板名称')).toHaveValue('current_account_draft');
    expect(screen.queryByText('模板已提交审核')).not.toBeInTheDocument();
  });

  it('switches between my templates and the public template library', async () => {
    const user = userEvent.setup();
    api.fetchPublicTemplates.mockResolvedValue({
      items: [{
        code: 'public-1', name: '公共欢迎模板', language: 'zh_CN', category: 'UTILITY', industries: [], usecase: null, topic: null,
        content: { templateName: '公共欢迎模板', sceneTemplateName: '公共欢迎模板', externalTemplateCode: null, languageCode: 'zh_CN', category: 'UTILITY', pages: [{ name: 'BODY', text: '你好', buttons: [] }], variables: [] },
      }], total: 1, page: 1, size: 20,
    });
    renderPage();
    expect(await screen.findByText('欢迎通知')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: '公共模板库' }));
    expect(await screen.findByText('公共欢迎模板')).toBeInTheDocument();
    expect(api.fetchPublicTemplates).toHaveBeenCalledWith('account-1', expect.objectContaining({ language: 'zh_CN', page: 1, size: 20 }));
  });

  it('queries the public template library again with the selected language', async () => {
    const user = userEvent.setup();
    api.fetchPublicTemplates.mockResolvedValue({ items: [], total: 0, page: 1, size: 20 });
    renderPage();

    await user.click(screen.getByRole('tab', { name: '公共模板库' }));
    await user.click(await screen.findByRole('combobox', { name: '公共模板语言' }));
    await user.click(await screen.findByText('西班牙语（西班牙）', { selector: '.ant-select-item-option-content' }));

    await waitFor(() => expect(api.fetchPublicTemplates).toHaveBeenLastCalledWith('account-1', expect.objectContaining({
      language: 'es_ES',
      page: 1,
      size: 20,
    })));
  });

  it('shows the display name and saves a local remark without submitting a template modification', async () => {
    const user = userEvent.setup();
    const remarkedTemplate = {
      ...template,
      remark: '客户欢迎',
      displayName: '客户欢迎（欢迎通知）',
    };
    const updatedTemplate = {
      ...remarkedTemplate,
      remark: '仓库发货提醒',
      displayName: '仓库发货提醒（欢迎通知）',
    };
    api.fetchAdminTemplates
      .mockResolvedValueOnce({ items: [remarkedTemplate], total: 1, page: 1, size: 20 })
      .mockResolvedValue({ items: [updatedTemplate], total: 1, page: 1, size: 20 });
    api.updateAdminTemplateRemark.mockResolvedValue(updatedTemplate);
    renderPage();

    expect(await screen.findByText('客户欢迎（欢迎通知）')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '编辑备注 welcome' }));
    const input = screen.getByRole('textbox', { name: '模板备注' });
    await user.clear(input);
    await user.type(input, '仓库发货提醒');
    await user.click(screen.getByRole('button', { name: '保存备注' }));

    await waitFor(() => expect(api.updateAdminTemplateRemark).toHaveBeenCalledWith(
      'account-1', 'welcome', 'zh_CN', '仓库发货提醒',
    ));
    expect(api.updateAdminTemplateRemark.mock.calls[0]).toHaveLength(4);
    expect(api.updateAdminTemplate).not.toHaveBeenCalled();
    expect(await screen.findByText('仓库发货提醒（欢迎通知）')).toBeInTheDocument();
  });

  it('keeps the remark editor open with the draft intact when saving fails', async () => {
    const user = userEvent.setup();
    api.updateAdminTemplateRemark.mockRejectedValue({ response: { data: { message: '备注保存失败' } } });
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '编辑备注 welcome' }));
    const input = screen.getByRole('textbox', { name: '模板备注' });
    await user.type(input, '新备注');
    await user.click(screen.getByRole('button', { name: '保存备注' }));

    const dialog = screen.getByRole('dialog', { name: '编辑模板备注' });
    expect(await within(dialog).findByText('备注保存失败')).toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: '模板备注' })).toHaveValue('新备注');
  });

  it('refreshes an open detail view from the complete remark response', async () => {
    const user = userEvent.setup();
    const updatedTemplate = {
      ...template,
      remark: '运营备注',
      displayName: '运营备注（欢迎通知）',
      name: '同步后官方名称',
    };
    api.updateAdminTemplateRemark.mockResolvedValue(updatedTemplate);
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '查看 welcome' }));
    expect(await within(screen.getByRole('dialog', { name: '模板详情' })).findByRole('heading', { name: '欢迎通知' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '编辑备注 welcome' }));
    await user.type(screen.getByRole('textbox', { name: '模板备注' }), '运营备注');
    await user.click(screen.getByRole('button', { name: '保存备注' }));

    expect(await within(screen.getByRole('dialog', { name: '模板详情' })).findByText('同步后官方名称')).toBeInTheDocument();
  });

  it('locks remark navigation while a save request is pending', async () => {
    let resolveSave!: (value: TemplateAdmin) => void;
    api.updateAdminTemplateRemark.mockImplementation(() => new Promise((resolve) => { resolveSave = resolve; }));
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '编辑备注 welcome' }));
    await user.click(screen.getByRole('button', { name: '保存备注' }));

    const dialog = screen.getByRole('dialog', { name: '编辑模板备注' });
    expect(within(dialog).queryByRole('button', { name: 'Close' })).not.toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: /取\s*消/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: '编辑备注 welcome' })).toBeDisabled();

    resolveSave(template);
  });

  it('uses the first active WhatsApp account and skips unrelated or inactive accounts', async () => {
    api.fetchChannelAccounts.mockResolvedValue([
      { ...account, id: 'email-1', channelType: 'email' },
      { ...account, id: 'inactive-wa', channelType: 'whatsapp', authStatus: 'inactive' },
      { ...account, id: 'active-wa', channelType: 'whatsapp', authStatus: 'active' },
    ]);

    renderPage();

    await screen.findByText('欢迎通知');
    expect(api.fetchAdminTemplates).toHaveBeenCalledWith('active-wa', { page: 1, size: 20 });
  });

  it('does not load templates when there is no active WhatsApp account', async () => {
    api.fetchChannelAccounts.mockResolvedValue([
      { ...account, id: 'email-1', channelType: 'email' },
      { ...account, id: 'inactive-wa', channelType: 'whatsapp', authStatus: 'inactive' },
    ]);

    renderPage();

    expect(await screen.findByText('没有可用的 WhatsApp 账号')).toBeInTheDocument();
    expect(api.fetchAdminTemplates).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '新建模板' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '同步模板' })).toBeDisabled();
  });

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
    await choose('语言', '简体中文');
    fireEvent.change(screen.getByLabelText('正文'), { target: { value: '订单 $(customer) 已就绪' } });
    await user.type(await screen.findByLabelText('customer 示例'), 'Ada');
    await user.click(screen.getByRole('button', { name: '提交创建' }));

    await waitFor(() => expect(api.createAdminTemplate).toHaveBeenCalledTimes(1));
    expect(api.createAdminTemplate).toHaveBeenCalledWith('account-1', expect.objectContaining({
      name: 'order_ready',
      language: 'zh_CN',
      category: 'UTILITY',
      components: expect.arrayContaining([
        expect.objectContaining({ type: 'BODY', text: '订单 $(customer) 已就绪' }),
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

  it('reuses the selected-file request id for the upload and every recovery read', async () => {
    const user = userEvent.setup();
    api.uploadTemplateMedia.mockResolvedValue({
      id: 'asset-1', clientRequestId: 'ignored-by-client', format: 'IMAGE', contentType: 'image/png', sizeBytes: 5,
      sha256: 'abc', providerUrl: null, assetStatus: 'PROCESSING', errorCode: null, errorMessage: null, traceId: 'trace-1',
    });
    api.fetchTemplateMediaUpload.mockResolvedValue({
      id: 'asset-1', clientRequestId: 'ignored-by-client', format: 'IMAGE', contentType: 'image/png', sizeBytes: 5,
      sha256: 'abc', providerUrl: 'https://provider.invalid/header.png', assetStatus: 'UPLOADED', errorCode: null, errorMessage: null, traceId: 'trace-1',
    });
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '新建模板' }));
    await choose('Header 类型', '图片');
    const file = new File(['image'], 'header.png', { type: 'image/png' });
    vi.useFakeTimers();
    fireEvent.change(screen.getByLabelText('Header 素材'), { target: { files: [file] } });
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(2_000);

    expect(api.fetchTemplateMediaUpload).toHaveBeenCalledTimes(1);
    const stableRequestId = api.uploadTemplateMedia.mock.calls[0][3];
    const uploadSignal = api.uploadTemplateMedia.mock.calls[0][4];
    expect(stableRequestId).toEqual(expect.any(String));
    expect(api.uploadTemplateMedia).toHaveBeenCalledWith('account-1', 'IMAGE', file, stableRequestId, uploadSignal);
    expect(api.fetchTemplateMediaUpload).toHaveBeenCalledWith('account-1', stableRequestId, uploadSignal);
  });

  it('queries after a network response loss but not after an explicit provider failure', async () => {
    const user = userEvent.setup();
    api.uploadTemplateMedia.mockRejectedValueOnce(new TypeError('network response lost'));
    api.fetchTemplateMediaUpload.mockResolvedValueOnce({
      id: 'asset-1', clientRequestId: 'request-1', format: 'IMAGE', contentType: 'image/png', sizeBytes: 5,
      sha256: 'abc', providerUrl: 'https://provider.invalid/header.png', assetStatus: 'UPLOADED', errorCode: null, errorMessage: null, traceId: 'trace-1',
    });
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '新建模板' }));
    await choose('Header 类型', '图片');
    vi.useFakeTimers();
    fireEvent.change(screen.getByLabelText('Header 素材'), {
      target: { files: [new File(['image'], 'first.png', { type: 'image/png' })] },
    });
    await Promise.resolve();
    await act(async () => { await vi.advanceTimersByTimeAsync(2_000); });
    expect(api.fetchTemplateMediaUpload).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: '替换素材' }));
    api.uploadTemplateMedia.mockRejectedValueOnce(Object.assign(new Error('provider failed'), {
      isAxiosError: true,
      response: { data: { message: '素材上传失败' } },
    }));
    fireEvent.change(screen.getByLabelText('Header 素材'), {
      target: { files: [new File(['image'], 'second.png', { type: 'image/png' })] },
    });
    await vi.advanceTimersByTimeAsync(2_000);

    expect(api.fetchTemplateMediaUpload).toHaveBeenCalledTimes(1);
    expect(screen.getByText('素材上传失败')).toBeInTheDocument();
  });

  it('keeps the newer selected file when an earlier upload completes later', async () => {
    const user = userEvent.setup();
    let resolveFirst!: (asset: unknown) => void;
    let resolveSecond!: (asset: unknown) => void;
    api.uploadTemplateMedia
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveSecond = resolve; }));
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '新建模板' }));
    await choose('Header 类型', '图片');
    const input = screen.getByLabelText('Header 素材');
    fireEvent.change(input, { target: { files: [new File(['first'], 'first.png', { type: 'image/png' })] } });
    await waitFor(() => expect(api.uploadTemplateMedia).toHaveBeenCalledTimes(1));
    fireEvent.change(input, { target: { files: [new File(['second'], 'second.png', { type: 'image/png' })] } });
    await waitFor(() => expect(api.uploadTemplateMedia).toHaveBeenCalledTimes(2));

    resolveSecond({ id: 'asset-second', clientRequestId: 'second', format: 'IMAGE', contentType: 'image/png', sizeBytes: 6, sha256: 'second', providerUrl: 'https://provider.invalid/second.png', assetStatus: 'UPLOADED', errorCode: null, errorMessage: null, traceId: 'trace-1' });
    expect(await screen.findByText('素材 ID：asset-second')).toBeInTheDocument();
    resolveFirst({ id: 'asset-first', clientRequestId: 'first', format: 'IMAGE', contentType: 'image/png', sizeBytes: 5, sha256: 'first', providerUrl: 'https://provider.invalid/first.png', assetStatus: 'UPLOADED', errorCode: null, errorMessage: null, traceId: 'trace-1' });
    await waitFor(() => expect(screen.queryByText('素材 ID：asset-first')).not.toBeInTheDocument());
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
    expect(api.updateAdminTemplate.mock.calls[0][3]).not.toHaveProperty('language');
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

  it('renders retired history as read-only without hiding its status or error', async () => {
    api.fetchTemplateOperations.mockResolvedValue([{
      ...succeededOperation,
      operationType: 'RETIRED',
      operationStatus: 'SUBMISSION_UNKNOWN',
      errorCode: 'OPERATION_RETIRED',
      errorMessage: '旧公共模板复制路径已退役',
    }]);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '查看 welcome' }));
    const drawer = await screen.findByRole('dialog', { name: '模板详情' });

    expect(await within(drawer).findByText('旧路径已退役')).toBeInTheDocument();
    expect(within(drawer).getByText('SUBMISSION_UNKNOWN：旧公共模板复制路径已退役')).toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: /重试/ })).not.toBeInTheDocument();
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
      isAxiosError: true,
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
    await choose('语言', '简体中文');
    await user.type(screen.getByLabelText('正文'), '消息正文');
    await user.click(screen.getByRole('button', { name: '提交创建' }));

    expect(await screen.findByText('提交结果未知，请通过操作记录和同步结果确认最终状态')).toBeInTheDocument();
  });

  it('keeps non-permission list failures visible and lets the operator retry', async () => {
    api.fetchAdminTemplates
      .mockRejectedValueOnce({ response: { status: 503, data: { message: '上游服务不可用', traceId: 'trace-503' } } })
      .mockResolvedValue({ items: [template], total: 1, page: 1, size: 20 });
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('上游服务不可用')).toBeInTheDocument();
    expect(screen.getByText('追踪 ID：trace-503')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '重试加载模板' }));
    expect(await screen.findByText('欢迎通知')).toBeInTheDocument();
    expect(api.fetchAdminTemplates).toHaveBeenCalledTimes(2);
  });

  it('shows explicit loading and empty states for the template list', async () => {
    let resolveList!: (value: { items: TemplateAdmin[]; total: number; page: number; size: number }) => void;
    api.fetchAdminTemplates.mockImplementation(() => new Promise((resolve) => { resolveList = resolve; }));
    renderPage();

    expect(await screen.findByText('正在加载模板')).toBeInTheDocument();
    await waitFor(() => expect(api.fetchAdminTemplates).toHaveBeenCalledTimes(1));
    resolveList({ items: [], total: 0, page: 1, size: 20 });
    expect(await screen.findByText('暂无模板')).toBeInTheDocument();
  });

  it('renders the stable template code and last-synced field on the card', async () => {
    renderPage();
    await screen.findByText('欢迎通知');

    expect(screen.getByText('welcome')).toBeInTheDocument();
    expect(screen.getByText('2026-08-11 08:05')).toBeInTheDocument();
  });

  it('retains distinct sync partial and complete-failure states', async () => {
    const user = userEvent.setup();
    api.syncAdminTemplates
      .mockResolvedValueOnce({ pages: 2, fetched: 8, changed: 3, complete: false })
      .mockRejectedValueOnce({ response: { data: { message: '连接超时' } } });
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '同步模板' }));
    expect(await screen.findByText('同步部分完成：8 个模板，3 个发生变化，请重试未完成部分')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '同步模板' }));
    expect(await screen.findByText('同步失败：连接超时')).toBeInTheDocument();
  });

  it('treats a completed zero-progress sync result as a complete failure', async () => {
    const user = userEvent.setup();
    api.syncAdminTemplates.mockResolvedValue({ pages: 0, fetched: 0, changed: 0, complete: false });
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '同步模板' }));
    expect(await screen.findByText('同步失败：未获取到任何模板，请检查同步条件后重试')).toBeInTheDocument();
    expect(screen.queryByText(/同步部分完成/)).not.toBeInTheDocument();
  });

  it('previews uploaded media and supports replacing it', async () => {
    const user = userEvent.setup();
    api.uploadTemplateMedia
      .mockResolvedValueOnce({ id: 'asset-1', format: 'IMAGE', contentType: 'image/png', sizeBytes: 5, sha256: 'one', providerUrl: 'https://provider.invalid/one.png', assetStatus: 'UPLOADED' })
      .mockResolvedValueOnce({ id: 'asset-2', format: 'IMAGE', contentType: 'image/png', sizeBytes: 6, sha256: 'two', providerUrl: 'https://provider.invalid/two.png', assetStatus: 'UPLOADED' });
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '新建模板' }));
    await choose('Header 类型', '图片');
    await user.upload(screen.getByLabelText('Header 素材'), new File(['one'], 'one.png', { type: 'image/png' }));
    expect(await screen.findByRole('img', { name: '已上传 Header 素材' })).toHaveAttribute('src', 'https://provider.invalid/one.png');
    await user.click(screen.getByRole('button', { name: '替换素材' }));
    expect(screen.queryByRole('img', { name: '已上传 Header 素材' })).not.toBeInTheDocument();
    await user.upload(screen.getByLabelText('Header 素材'), new File(['two'], 'two.png', { type: 'image/png' }));
    expect(await screen.findByRole('img', { name: '已上传 Header 素材' })).toHaveAttribute('src', 'https://provider.invalid/two.png');
  });

  it('edits URL and phone button targets and removes dynamic rows', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '新建模板' }));
    await user.click(screen.getByRole('button', { name: '添加按钮' }));
    await choose('按钮 1 类型', '网址');
    await user.type(screen.getByLabelText('按钮 1 网址'), 'https://example.com');
    await choose('按钮 1 类型', '电话');
    await user.type(screen.getByLabelText('按钮 1 电话'), '+8613800138000');
    expect(screen.getByLabelText('按钮 1 电话')).toHaveValue('+8613800138000');
    await user.click(screen.getByRole('button', { name: '删除按钮 1' }));
    expect(screen.queryByLabelText('按钮 1 类型')).not.toBeInTheDocument();
  });

  it('merges BODY and text Header variables without duplicating shared examples', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '新建模板' }));
    fireEvent.change(screen.getByLabelText('正文'), { target: { value: '正文 $(shared) $(bodyOnly)' } });
    await choose('Header 类型', '文本');
    fireEvent.change(screen.getByLabelText('Header 文本'), { target: { value: '标题 $(shared) $(headerOnly)' } });
    expect(await screen.findByLabelText('shared 示例')).toBeInTheDocument();
    expect(screen.getByLabelText('bodyOnly 示例')).toBeInTheDocument();
    expect(screen.getByLabelText('headerOnly 示例')).toBeInTheDocument();
    expect(screen.getAllByLabelText('shared 示例')).toHaveLength(1);
  });

  it('preserves stored BODY and text Header examples when editing without changes', async () => {
    const editableTemplate: TemplateAdmin = {
      ...template,
      templateCode: 'order_update',
      name: '订单更新',
      components: [
        { type: 'HEADER', headerFormat: 'TEXT', text: '尊敬的 $(customer)', mediaAssetId: null, buttons: [] },
        { type: 'BODY', headerFormat: null, text: '订单 $(orderNo) 已更新', mediaAssetId: null, buttons: [] },
      ],
      examples: { customer: ['张三'], orderNo: ['A-100'] },
    };
    const user = userEvent.setup();
    const submit = vi.fn().mockResolvedValue(undefined);
    render(<TemplateEditorDrawer open template={editableTemplate} uploadMedia={vi.fn()} onClose={vi.fn()} onSubmit={submit} />);
    await screen.findByLabelText('customer 示例');
    await user.click(screen.getByRole('button', { name: '提交修改' }));

    await waitFor(() => expect(submit).toHaveBeenCalledWith(
      expect.objectContaining({
        examples: { customer: ['张三'], orderNo: ['A-100'] },
      }),
      true,
    ));
  });

  it('clears stale operation history, ignores older responses, and exposes history failures', async () => {
    const newerTemplate: TemplateAdmin = { ...template, id: 'template-2', templateCode: 'follow_up', name: '跟进通知', displayName: '跟进通知' };
    let resolveFirst!: (value: TemplateOperation[]) => void;
    let resolveSecond!: (value: TemplateOperation[]) => void;
    api.fetchAdminTemplates.mockResolvedValue({ items: [template, newerTemplate], total: 2, page: 1, size: 20 });
    api.fetchTemplateOperations.mockImplementation((_accountId: string, code: string) => new Promise((resolve) => {
      if (code === 'welcome') resolveFirst = resolve;
      else resolveSecond = resolve;
    }));
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('欢迎通知');

    await user.click(screen.getByRole('button', { name: '查看 welcome' }));
    await user.click(screen.getByRole('button', { name: '查看 follow_up' }));
    expect(await screen.findByText('正在加载操作记录')).toBeInTheDocument();
    resolveSecond([{ ...succeededOperation, operationId: 'operation-2', operationType: 'MODIFY', templateCode: 'follow_up' }]);
    expect(await screen.findByText('MODIFY')).toBeInTheDocument();
    resolveFirst([succeededOperation]);
    await waitFor(() => expect(screen.queryByText('CREATE')).not.toBeInTheDocument());

    api.fetchTemplateOperations.mockRejectedValueOnce({ response: { data: { message: '操作记录加载失败' } } });
    await user.click(screen.getByRole('button', { name: '查看 welcome' }));
    expect(await screen.findByText('操作记录加载失败')).toBeInTheDocument();
  });
});
