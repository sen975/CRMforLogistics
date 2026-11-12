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

/**
 * 把正文改成「变量不在首尾」的合规形态。
 *
 * 公共模板库那条基础模板（account_creation_confirmation_3）的原文是
 * 「$(text)，您好：您的新帐号已成功创建。」—— 变量开头，正是平台会拒审的形态
 * （拒审原话 Variables can't be at the start or end of the template），编辑器也会因此拦住提交。
 * 所以凡是走到「能不能提交」这一步的用例，都得先像真实用户那样把它改掉。
 */
function makeBodySubmittable() {
  fireEvent.change(screen.getByLabelText('正文'), { target: { value: '您好，$(text)，您的新帐号已成功创建。' } });
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
    makeBodySubmittable();

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
    makeBodySubmittable();

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
    makeBodySubmittable();

    expect(screen.getByRole('button', { name: '提交创建' })).toBeEnabled();
    expect(screen.getByRole('combobox', { name: '语言' }).closest('.ant-select')).toHaveTextContent('法语');
  });

  it('rejects unsupported placeholder syntax before submission', () => {
    renderEditor();

    fireEvent.change(screen.getByLabelText('正文'), { target: { value: '您好 ${text}' } });

    expect(screen.getByText('变量只能使用 $(name) 格式')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '提交创建' })).toBeDisabled();
  });

  /**
   * 这条钉的是改动的动机本身：公共模板库里的真实内容就可能违规。
   *
   * 平台那条基础模板的正文是「$(text)，您好：您的新帐号已成功创建。」—— 变量开头。
   * 照原样提交，平台必然拒审：用户白等一轮审核，还要换个模板名重走。
   * 编辑器在本地就拦住，并把原因写出来，用户才知道该改哪儿。
   */
  it('blocks a public-template body that starts with a variable', () => {
    renderEditor();

    expect(screen.getByLabelText('正文')).toHaveValue('$(text)，您好：您的新帐号已成功创建。');
    expect(screen.getByText('变量不能出现在正文的开头或结尾')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '提交创建' })).toBeDisabled();

    makeBodySubmittable();

    expect(screen.queryByText('变量不能出现在正文的开头或结尾')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '提交创建' })).toBeEnabled();
  });

  /** 页脚一个变量都不能有 —— 平台 FOOTER 组件不支持参数，与正文那条判据不同。 */
  it('blocks a footer that carries a variable', () => {
    renderEditor();
    makeBodySubmittable();

    fireEvent.change(screen.getByLabelText('Footer'), { target: { value: '退订请回 $(text)' } });

    expect(screen.getByText('页脚不能包含变量')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '提交创建' })).toBeDisabled();
  });
});

describe('TemplateEditorDrawer existing templates', () => {
  it('shows the official name as text and submits an editable business remark', async () => {
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

    expect(screen.getByText('official_welcome')).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: '模板名称' })).not.toBeInTheDocument();
    const remarkInput = screen.getByRole('textbox', { name: '业务备注' });
    expect(remarkInput).toHaveValue('客户欢迎');
    await user.clear(remarkInput);
    await user.type(remarkInput, '新客户欢迎');

    await user.click(screen.getByRole('button', { name: '提交修改' }));
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ name: 'official_welcome' }), true, '新客户欢迎');
  });
});
