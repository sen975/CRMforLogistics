import { describe, expect, it } from 'vitest';
import type { PublicTemplate } from '../../api/types';
import { publicTemplateToEditorDraft } from './publicTemplateConversion';

function template(overrides: Partial<PublicTemplate> = {}): PublicTemplate {
  return {
    code: '1174248293003616256',
    name: 'account_creation_confirmation_3',
    language: 'zh_CN',
    category: 'UTILITY',
    industries: ['ecommerce'],
    usecase: 'account creation',
    topic: 'account',
    content: {
      templateName: 'account_creation_confirmation_3',
      sceneTemplateName: null,
      externalTemplateCode: null,
      languageCode: 'zh_CN',
      category: 'UTILITY',
      pages: [{
        name: 'BODY',
        text: '$(text)，您好：您的新帐号已成功创建。',
        buttons: [{ name: '验证帐号', type: 'visitWebsite', url: 'https://www.example.com' }],
      }],
      variables: [{ code: 'text', name: '姓名', example: 'John', format: 'TEXT' }],
    },
    ...overrides,
  };
}

describe('publicTemplateToEditorDraft', () => {
  it('converts one selected public-template page into an editor draft', () => {
    const draft = publicTemplateToEditorDraft(template(), 0);

    expect(draft.initialValue).toMatchObject({
      name: 'account_creation_confirmation_3_custom',
      language: 'zh_CN',
      category: 'UTILITY',
      body: '$(text)，您好：您的新帐号已成功创建。',
      examples: { text: ['John'] },
      buttons: [{ type: 'URL', text: '验证帐号', url: 'https://www.example.com', phoneNumber: null }],
    });
    expect(draft.issues).toEqual([]);
  });

  it('requires an explicit page selection when the source has multiple pages', () => {
    const source = template({
      content: {
        ...template().content,
        pages: [
          { name: 'first', text: 'first', buttons: [] },
          { name: 'second', text: 'second', buttons: [] },
        ],
      },
    });

    const draft = publicTemplateToEditorDraft(source, null);

    expect(draft.selectedPageIndex).toBeNull();
    expect(draft.initialValue.body).toBe('');
    expect(draft.issues).toEqual([expect.objectContaining({ code: 'PAGE_SELECTION_REQUIRED', field: 'page', blocking: true })]);
  });

  it('does not select a page when the requested index is out of range', () => {
    const draft = publicTemplateToEditorDraft(template(), 1);

    expect(draft.selectedPageIndex).toBeNull();
    expect(draft.initialValue.body).toBe('');
    expect(draft.issues).toEqual([expect.objectContaining({ code: 'PAGE_SELECTION_REQUIRED', field: 'page', blocking: true })]);
  });

  it('reports a missing selected-page body', () => {
    const source = template({
      content: { ...template().content, pages: [{ name: 'BODY', text: null, buttons: [] }] },
    });

    expect(publicTemplateToEditorDraft(source, 0).issues).toEqual([
      expect.objectContaining({ code: 'BODY_MISSING', field: 'body', blocking: true }),
    ]);
  });

  it('keeps over-limit editable values visible and blocks submission', () => {
    const source = template({
      name: 'n'.repeat(510),
      content: {
        ...template().content,
        pages: [{ name: 'BODY', text: 'b'.repeat(1025), buttons: [] }],
      },
    });

    const draft = publicTemplateToEditorDraft(source, 0);

    expect(draft.initialValue.name).toBe(`${source.name}_custom`);
    expect(draft.initialValue.body).toBe('b'.repeat(1025));
    expect(draft.issues).toEqual(expect.arrayContaining([
      expect.objectContaining({ code: 'NAME_TOO_LONG', field: 'name', blocking: true }),
      expect.objectContaining({ code: 'BODY_TOO_LONG', field: 'body', blocking: true }),
    ]));
  });

  it('preserves unsupported language and category as unselected editor values', () => {
    const source = template({ language: 'fr_FR', category: 'AUTHENTICATION' });

    const draft = publicTemplateToEditorDraft(source, 0);

    expect(draft.initialValue.language).toBe('');
    expect(draft.initialValue.category).toBeNull();
    expect(draft.issues).toEqual(expect.arrayContaining([
      expect.objectContaining({ code: 'LANGUAGE_UNSUPPORTED', field: 'language', blocking: true }),
      expect.objectContaining({ code: 'CATEGORY_UNSUPPORTED', field: 'category', blocking: true }),
    ]));
  });

  it('does not turn undeclared provider tokens into editor variables', () => {
    const source = template({
      content: {
        ...template().content,
        pages: [{ name: 'BODY', text: '您好，$(text) $(missing)', buttons: [] }],
        variables: [
          { code: 'text', name: '姓名', example: 'John', format: 'TEXT' },
          { code: 'unused', name: '未使用变量', example: 'Should not be copied', format: 'TEXT' },
        ],
      },
    });

    const draft = publicTemplateToEditorDraft(source, 0);

    expect(draft.initialValue.body).toBe('您好，$(text) $(missing)');
    expect(draft.initialValue.examples).toEqual({ text: ['John'] });
    expect(draft.initialValue.examples).not.toHaveProperty('unused');
    expect(draft.issues).toEqual([expect.objectContaining({ code: 'VARIABLE_UNDECLARED', field: 'variables', blocking: true })]);
  });

  it('does not treat unsupported placeholder syntax as variables', () => {
    const source = template({
      content: {
        ...template().content,
        pages: [{ name: 'BODY', text: '您好，${text} ${missing}', buttons: [] }],
        variables: [
          { code: 'text', name: '姓名', example: 'John', format: 'TEXT' },
          { code: 'unused', name: '未使用变量', example: 'Should not be copied', format: 'TEXT' },
        ],
      },
    });

    const draft = publicTemplateToEditorDraft(source, 0);

    expect(draft.initialValue.body).toBe('您好，${text} ${missing}');
    expect(draft.initialValue.examples).toEqual({});
    expect(draft.issues).toEqual([]);
  });

  it.each([null, '', '  \n\t '])('blocks a missing page body while preserving its editable value: %j', (text) => {
    const source = template({
      content: { ...template().content, pages: [{ name: 'BODY', text, buttons: [] }] },
    });

    const draft = publicTemplateToEditorDraft(source, 0);

    expect(draft.initialValue.body).toBe(text ?? '');
    expect(draft.issues).toEqual([
      expect.objectContaining({ code: 'BODY_MISSING', field: 'body', blocking: true }),
    ]);
  });

  it('keeps unsupported provider buttons visible as unresolved source buttons', () => {
    const source = template({
      content: {
        ...template().content,
        pages: [{
          name: 'BODY',
          text: '欢迎',
          buttons: [
            { name: '验证帐号', type: 'visitWebsite', url: 'https://www.example.com' },
            { name: '联系客服', type: 'callPhone', url: 'tel:+8613800000000' },
          ],
        }],
      },
    });

    const draft = publicTemplateToEditorDraft(source, 0);

    expect(draft.initialValue.buttons).toEqual([
      { type: 'URL', text: '验证帐号', url: 'https://www.example.com', phoneNumber: null },
    ]);
    expect(draft.unresolvedButtons).toEqual([
      { id: '0:1', button: { name: '联系客服', type: 'callPhone', url: 'tel:+8613800000000' } },
    ]);
    expect(draft.issues).toEqual([expect.objectContaining({ code: 'BUTTON_UNSUPPORTED', field: 'buttons', blocking: true })]);
  });

  it.each([
    { name: '  ', type: 'visitWebsite', url: 'https://www.example.com' },
    { name: '验证帐号', type: 'visitWebsite', url: '  ' },
    { name: null, type: 'visitWebsite', url: 'https://www.example.com' },
    { name: '验证帐号', type: 'visitWebsite', url: null },
  ])('keeps incomplete visitWebsite buttons unresolved', (button) => {
    const source = template({
      content: { ...template().content, pages: [{ name: 'BODY', text: '欢迎', buttons: [button] }] },
    });

    const draft = publicTemplateToEditorDraft(source, 0);

    expect(draft.initialValue.buttons).toEqual([]);
    expect(draft.unresolvedButtons).toEqual([{ id: '0:0', button }]);
    expect(draft.issues).toEqual([
      expect.objectContaining({ code: 'BUTTON_UNSUPPORTED', field: 'buttons', blocking: true }),
    ]);
  });
});
