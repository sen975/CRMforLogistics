import { App as AntApp, ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PublicTemplateLibrary from './PublicTemplateLibrary';
import type { PublicTemplate, PublicTemplateListPage } from '../../api/types';
import type { PublicTemplateConversionResult } from './publicTemplateConversion';

const api = vi.hoisted(() => ({
  fetchPublicTemplates: vi.fn(),
}));

vi.mock('../../api/endpoints', () => api);

const account = {
  id: 'account-1',
  channelType: 'chatapp',
  name: 'WhatsApp 主账号',
  accountIdentifier: 'wa-primary',
  authStatus: 'active',
  syncStatus: 'idle',
  lastSyncedAt: null,
  createdAt: '2026-08-01T00:00:00Z',
};

const publicTemplate: PublicTemplate = {
  code: '1174248293003616256',
  name: 'account_creation_confirmation_3',
  language: 'zh_CN',
  category: 'UTILITY',
  industries: ['电商'],
  usecase: '账号创建确认',
  topic: '账号',
  content: {
    templateName: 'account_creation_confirmation_3',
    sceneTemplateName: 'scene_account_confirmation',
    externalTemplateCode: 'external-1',
    languageCode: 'zh_CN',
    category: 'UTILITY',
    pages: [{
      name: 'BODY',
      text: '您的账号 $(text) 已创建',
      buttons: [{ name: '查看详情', type: 'visitWebsite', url: 'https://example.com' }],
    }],
    variables: [{ code: 'text', name: '账号名称', example: '示例账号', format: 'TEXT' }],
  },
};

const page: PublicTemplateListPage = { items: [publicTemplate], total: 1, page: 1, size: 20 };

function renderLibrary(
  selectedAccount: typeof account | null = account,
  onCreateFromPublicTemplate: (draft: PublicTemplateConversionResult) => void = vi.fn(),
) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <AntApp>
          <PublicTemplateLibrary hasWhatsAppAccount={Boolean(selectedAccount)} onCreateFromPublicTemplate={onCreateFromPublicTemplate} />
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>,
  );
  return {
    onCreateFromPublicTemplate,
    queryClient,
    ...rendered,
  };
}

async function openDetail(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: `查看公共模板 ${publicTemplate.code}` }));
  return screen.findByRole('dialog', { name: publicTemplate.name });
}

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchPublicTemplates.mockResolvedValue(page);
});

afterEach(() => vi.restoreAllMocks());

describe('PublicTemplateLibrary', () => {
  it('renders accessible preview cards instead of a table and forwards filters', async () => {
    const user = userEvent.setup();
    renderLibrary();

    expect(await screen.findByRole('button', { name: `查看公共模板 ${publicTemplate.code}` })).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('公共模板搜索'), 'account');
    await user.type(screen.getByLabelText('行业筛选'), '电商');
    await user.type(screen.getByLabelText('用途筛选'), '账号创建确认');

    await waitFor(() => expect(api.fetchPublicTemplates).toHaveBeenLastCalledWith(expect.objectContaining({
      name: 'account', language: 'zh_CN', industries: ['电商'], usecases: ['账号创建确认'], page: 1, size: 20,
    })));
  });

  it('opens a workbench that switches between parameter and example previews', async () => {
    const user = userEvent.setup();
    renderLibrary();
    const dialog = await openDetail(user);

    expect(within(dialog).getByRole('radio', { name: '参数' })).toBeChecked();
    expect(within(dialog).getByText('text')).toHaveClass('template-variable');
    await user.click(within(dialog).getByText('示例'));
    expect(within(dialog).getByText('示例账号')).toHaveClass('template-variable');
    expect(within(dialog).queryByText('BODY')).not.toBeInTheDocument();
  });

  it('offers only create-from-template and forwards the converted draft', async () => {
    const onCreateFromPublicTemplate = vi.fn();
    const user = userEvent.setup();
    renderLibrary(account, onCreateFromPublicTemplate);
    const dialog = await openDetail(user);

    expect(within(dialog).queryByRole('button', { name: '复制此模板' })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: '基于此模板创建' }));

    expect(onCreateFromPublicTemplate).toHaveBeenCalledWith(expect.objectContaining({
      sourceTemplate: publicTemplate,
      selectedPageIndex: 0,
      initialValue: expect.objectContaining({
        name: 'account_creation_confirmation_3_custom',
        language: 'zh_CN',
        category: 'UTILITY',
        body: '您的账号 $(text) 已创建',
        buttons: [expect.objectContaining({ type: 'URL', text: '查看详情', url: 'https://example.com' })],
      }),
    }));
  });

  it('requires an explicit page choice for multi-page custom creation', async () => {
    const multiPageTemplate: PublicTemplate = {
      ...publicTemplate,
      code: 'multi-page',
      content: {
        ...publicTemplate.content,
        pages: [
          { name: '第一页', text: '第一页正文 $(text)', buttons: [] },
          { name: '第二页', text: '第二页正文 $(text)', buttons: [] },
        ],
      },
    };
    api.fetchPublicTemplates.mockResolvedValue({ ...page, items: [multiPageTemplate] });
    const onCreateFromPublicTemplate = vi.fn();
    const user = userEvent.setup();
    renderLibrary(account, onCreateFromPublicTemplate);
    await user.click(await screen.findByRole('button', { name: `查看公共模板 ${multiPageTemplate.code}` }));
    const dialog = await screen.findByRole('dialog', { name: multiPageTemplate.name });

    expect(within(dialog).getByText('请选择创建来源页面')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '基于此模板创建' })).toBeDisabled();
    await user.click(within(dialog).getByRole('combobox', { name: '创建来源页面' }));
    await user.click(await screen.findByText('第二页'));
    await user.click(within(dialog).getByRole('button', { name: '基于此模板创建' }));

    expect(onCreateFromPublicTemplate).toHaveBeenCalledWith(expect.objectContaining({
      sourceTemplate: multiPageTemplate,
      selectedPageIndex: 1,
    }));
  });

  it('blocks custom creation when the public template has no pages', async () => {
    const noPageTemplate: PublicTemplate = {
      ...publicTemplate,
      code: 'no-page',
      content: { ...publicTemplate.content, pages: [] },
    };
    api.fetchPublicTemplates.mockResolvedValue({ ...page, items: [noPageTemplate] });
    const onCreateFromPublicTemplate = vi.fn();
    const user = userEvent.setup();
    renderLibrary(account, onCreateFromPublicTemplate);
    await user.click(await screen.findByRole('button', { name: `查看公共模板 ${noPageTemplate.code}` }));
    const dialog = await screen.findByRole('dialog', { name: noPageTemplate.name });
    const customize = within(dialog).getByRole('button', { name: '基于此模板创建' });

    expect(within(dialog).getByText('公共模板没有可用于创建的页面')).toBeInTheDocument();
    expect(customize).toBeDisabled();
    await user.click(customize);
    expect(onCreateFromPublicTemplate).not.toHaveBeenCalled();
  });

  it('shows same-sized loading skeletons and the empty state', async () => {
    api.fetchPublicTemplates.mockImplementation(() => new Promise(() => undefined));
    const view = renderLibrary();
    expect(await screen.findByLabelText('正在加载公共模板')).toBeInTheDocument();
    view.unmount();

    api.fetchPublicTemplates.mockResolvedValue({ ...page, items: [], total: 0 });
    renderLibrary();
    expect(await screen.findByText('暂无公共模板')).toBeInTheDocument();
  });

  it('keeps server pagination and retries the structured load error', async () => {
    const user = userEvent.setup();
    api.fetchPublicTemplates
      .mockRejectedValueOnce({ response: { data: { message: '加载失败', traceId: 'trace-load' } } })
      .mockResolvedValueOnce({ ...page, total: 40 })
      .mockResolvedValue({ ...page, total: 40, items: [{ ...publicTemplate, code: 'page-2' }], page: 2 });
    renderLibrary();
    expect(await screen.findByText('加载失败')).toBeInTheDocument();
    expect(screen.getByText('追踪 ID：trace-load')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '重试加载' }));
    expect(await screen.findByRole('button', { name: `查看公共模板 ${publicTemplate.code}` })).toBeInTheDocument();
    fireEvent.click(screen.getByTitle('Next Page'));
    await waitFor(() => expect(api.fetchPublicTemplates).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2, size: 20 })));
  });

  it('does not query without an account and closes the detail when the account changes', async () => {
    renderLibrary(null);
    expect(screen.getByText('没有可用的 WhatsApp 账号')).toBeInTheDocument();
    expect(screen.getByText('配置 WhatsApp 账号后即可浏览公共模板')).toBeInTheDocument();
    expect(api.fetchPublicTemplates).not.toHaveBeenCalled();

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = render(
      <QueryClientProvider client={queryClient}><ConfigProvider><AntApp><PublicTemplateLibrary hasWhatsAppAccount /></AntApp></ConfigProvider></QueryClientProvider>,
    );
    const user = userEvent.setup();
    const dialog = await openDetail(user);
    expect(dialog).toBeInTheDocument();
    view.rerender(
      <QueryClientProvider client={queryClient}><ConfigProvider><AntApp><PublicTemplateLibrary hasWhatsAppAccount={false} /></AntApp></ConfigProvider></QueryClientProvider>,
    );

    await waitFor(() => expect(screen.queryByRole('dialog', { name: publicTemplate.name })).not.toBeInTheDocument());
    await waitFor(() => expect(api.fetchPublicTemplates).toHaveBeenCalled());
  });
});
