import { ConfigProvider } from 'antd';
import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { PublicTemplate, SharedTemplate } from '../../api/types';
import { publicTemplateToEditorDraft } from './publicTemplateConversion';
import TemplateEditorDrawer from './TemplateEditorDrawer';

const publicTemplate: PublicTemplate = {
  code: 'public-1',
  name: 'account_creation_confirmation_3',
  language: 'zh_CN',
  category: 'UTILITY',
  industries: [],
  usecase: null,
  topic: null,
  content: {
    templateName: 'account_creation_confirmation_3',
    sceneTemplateName: 'account_creation_confirmation_3',
    externalTemplateCode: null,
    languageCode: 'zh_CN',
    category: 'UTILITY',
    pages: [{
      name: 'BODY',
      text: '$(text)，您好：您的新帐号已成功创建。',
      buttons: [],
    }],
    variables: [{ code: 'text', name: '姓名', example: 'John', format: 'TEXT' }],
  },
};

function renderEditor(template: PublicTemplate = publicTemplate) {
  const draft = publicTemplateToEditorDraft(template, 0);
  const submit = vi.fn().mockResolvedValue(undefined);
  render(
    <ConfigProvider>
      <TemplateEditorDrawer
        open
        template={null}
        initialValue={draft.initialValue}
        sourceContext={draft}
        uploadMedia={vi.fn()}
        onClose={vi.fn()}
        onSubmit={submit}
      />
    </ConfigProvider>,
  );
  return { draft, submit };
}

async function choose(label: string, option: string) {
  const controls = screen.getAllByRole('combobox', { name: label });
  fireEvent.mouseDown(controls[controls.length - 1]);
  await userEvent.setup().click(await screen.findByText(option, { selector: '.ant-select-item-option-content' }));
}

describe('TemplateEditorDrawer public-template drafts', () => {
  it('initializes the existing editor from a public-template draft', () => {
    renderEditor();

    expect(screen.getByLabelText('模板名称')).toHaveValue('account_creation_confirmation_3_custom');
    expect(screen.getByLabelText('模板名称')).not.toHaveAttribute('readonly');
    expect(screen.getByLabelText('正文')).toHaveValue('$(text)，您好：您的新帐号已成功创建。');
    expect(screen.getByLabelText('text 示例')).toHaveValue('John');
  });

  it('requires an explicit structured disposition for an unresolved source button', async () => {
    const user = userEvent.setup();
    const unresolvedTemplate: PublicTemplate = {
      ...publicTemplate,
      content: {
        ...publicTemplate.content,
        pages: [{
          ...publicTemplate.content.pages[0],
          buttons: [{ name: 'unknown_button', type: 'UNSUPPORTED', url: null }],
        }],
      },
    };
    renderEditor(unresolvedTemplate);

    expect(screen.getByRole('button', { name: '提交创建' })).toBeDisabled();
    const control = screen.getAllByRole('combobox', { name: '源按钮 unknown_button 处理方式' });
    fireEvent.mouseDown(control[control.length - 1]);
    await user.click(await screen.findByText('不添加', { selector: '.ant-select-item-option-content' }));
    expect(screen.getByRole('button', { name: '提交创建' })).toBeEnabled();
  });

  it('requires supported language and category choices without changing conversion issues', async () => {
    const unsupportedTemplate: PublicTemplate = {
      ...publicTemplate,
      language: 'fr_FR',
      category: 'AUTHENTICATION',
    };
    const { draft } = renderEditor(unsupportedTemplate);
    const originalIssues = [...draft.issues];

    expect(screen.getByRole('button', { name: '提交创建' })).toBeDisabled();
    await choose('语言', '简体中文');
    expect(screen.getByRole('button', { name: '提交创建' })).toBeDisabled();
    await choose('模板类别', '工具');

    expect(screen.getByRole('button', { name: '提交创建' })).toBeEnabled();
    expect(draft.issues).toEqual(originalIssues);
  });

  it('accepts a supported non-English language from a public template', () => {
    const frenchTemplate: PublicTemplate = {
      ...publicTemplate,
      language: 'fr',
      content: { ...publicTemplate.content, languageCode: 'fr' },
    };
    renderEditor(frenchTemplate);

    expect(screen.getByRole('button', { name: '提交创建' })).toBeEnabled();
    expect(screen.getByRole('combobox', { name: '语言' }).closest('.ant-select')).toHaveTextContent('法语');
  });

  it('rejects unsupported placeholder syntax before submission', () => {
    renderEditor();

    fireEvent.change(screen.getByLabelText('正文'), { target: { value: '您好 ${text}' } });

    expect(screen.getByText('变量只能使用 $(name) 格式')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '提交创建' })).toBeDisabled();
  });
});

describe('TemplateEditorDrawer existing templates', () => {
  it('keeps the official template name read-only and submits the original name', async () => {
    const user = userEvent.setup();
    const submit = vi.fn().mockResolvedValue(undefined);
    const template: SharedTemplate = {
      id: 'template-1',
      version: 1,
      templateCode: 'welcome',
      name: 'official_welcome',
      remark: '客户欢迎',
      displayName: '客户欢迎（official_welcome）',
      language: 'zh_CN',
      category: 'UTILITY',
      reviewStatus: 'APPROVED',
      providerAuditStatus: 'pass',
      rejectionReason: null,
      allowSend: true,
      components: [{ type: 'BODY', headerFormat: null, text: '您好', mediaAssetId: null, buttons: [] }],
      examples: {},
      messageSendTtlSeconds: null,
      qualityScore: null,
      providerUpdatedAt: null,
      lastSyncedAt: null,
      deletedAt: null,
    };

    render(
      <ConfigProvider>
        <TemplateEditorDrawer
          open
          template={template}
          uploadMedia={vi.fn()}
          onClose={vi.fn()}
          onSubmit={submit}
        />
      </ConfigProvider>,
    );

    const nameInput = screen.getByLabelText('模板名称');
    expect(nameInput).toHaveAttribute('readonly');
    await user.type(nameInput, '_changed');
    expect(nameInput).toHaveValue('official_welcome');

    await user.click(screen.getByRole('button', { name: '提交修改' }));
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ name: 'official_welcome' }), true);
  });
});
