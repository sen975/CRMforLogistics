import type { PublicTemplate, PublicTemplateButton, PublicTemplateVariable, TemplateButton } from '../../api/types';
import {
  isTemplateCategory,
  isTemplateLanguage,
  MAX_TEMPLATE_BODY_LENGTH,
  MAX_TEMPLATE_NAME_LENGTH,
  type TemplateEditorInitialValue,
  variableNames,
} from './templateUi';

export type PublicTemplateConversionIssueCode =
  | 'PAGE_SELECTION_REQUIRED'
  | 'NAME_TOO_LONG'
  | 'BODY_MISSING'
  | 'BODY_TOO_LONG'
  | 'LANGUAGE_UNSUPPORTED'
  | 'CATEGORY_UNSUPPORTED'
  | 'VARIABLE_UNDECLARED'
  | 'BUTTON_UNSUPPORTED';

export interface PublicTemplateConversionIssue {
  id: string;
  code: PublicTemplateConversionIssueCode;
  field: 'page' | 'name' | 'body' | 'language' | 'category' | 'variables' | 'buttons';
  message: string;
  blocking: true;
}

export interface PublicTemplateConversionResult {
  sourceTemplate: PublicTemplate;
  selectedPageIndex: number | null;
  initialValue: TemplateEditorInitialValue;
  issues: PublicTemplateConversionIssue[];
  unresolvedButtons: Array<{ id: string; button: PublicTemplateButton }>;
}

function issue(
  id: string,
  code: PublicTemplateConversionIssueCode,
  field: PublicTemplateConversionIssue['field'],
  message: string,
): PublicTemplateConversionIssue {
  return { id, code, field, message, blocking: true };
}

function editorBody(text: string, variables: PublicTemplateVariable[]): { body: string; unknown: string[] } {
  const declared = new Set(variables.map((variable) => variable.code));
  const unknown = new Set<string>();
  for (const code of variableNames(text)) {
    if (!declared.has(code)) unknown.add(code);
  }
  return { body: text, unknown: [...unknown] };
}

function examplesForBody(body: string, variables: PublicTemplateVariable[]): Record<string, string[]> {
  const variablesByCode = new Map(variables.map((variable) => [variable.code, variable]));
  const examples: Record<string, string[]> = {};
  for (const code of variableNames(body)) {
    const variable = variablesByCode.get(code);
    if (variable) examples[code] = [variable.example ?? ''];
  }
  return examples;
}

function selectedPageIndex(template: PublicTemplate, pageIndex: number | null): number | null {
  if (pageIndex === null) return template.content.pages.length === 1 ? 0 : null;
  if (!Number.isInteger(pageIndex) || pageIndex < 0 || pageIndex >= template.content.pages.length) return null;
  return pageIndex;
}

function convertedButtons(
  buttons: PublicTemplateButton[],
  pageIndex: number,
): { buttons: TemplateButton[]; unresolvedButtons: Array<{ id: string; button: PublicTemplateButton }>; issues: PublicTemplateConversionIssue[] } {
  const resolved: TemplateButton[] = [];
  const unresolvedButtons: Array<{ id: string; button: PublicTemplateButton }> = [];
  const issues: PublicTemplateConversionIssue[] = [];

  buttons.forEach((button, buttonIndex) => {
    if (
      button.type === 'visitWebsite'
      && typeof button.name === 'string'
      && button.name.trim()
      && typeof button.url === 'string'
      && button.url.trim()
    ) {
      resolved.push({ type: 'URL', text: button.name, url: button.url, phoneNumber: null });
      return;
    }
    const id = `${pageIndex}:${buttonIndex}`;
    unresolvedButtons.push({ id, button });
    issues.push(issue(`button-unsupported:${id}`, 'BUTTON_UNSUPPORTED', 'buttons', '公共模板按钮类型不支持自动转换'));
  });

  return { buttons: resolved, unresolvedButtons, issues };
}

export function publicTemplateToEditorDraft(template: PublicTemplate, pageIndex: number | null): PublicTemplateConversionResult {
  const selectedIndex = selectedPageIndex(template, pageIndex);
  const issues: PublicTemplateConversionIssue[] = [];
  const name = `${template.name}_custom`;
  const language = isTemplateLanguage(template.language) ? template.language : '';
  const category = isTemplateCategory(template.category) ? template.category : null;

  if (name.length > MAX_TEMPLATE_NAME_LENGTH) {
    issues.push(issue('name-too-long', 'NAME_TOO_LONG', 'name', `模板名称不能超过 ${MAX_TEMPLATE_NAME_LENGTH} 个字符`));
  }
  if (!isTemplateLanguage(template.language)) {
    issues.push(issue('language-unsupported', 'LANGUAGE_UNSUPPORTED', 'language', '公共模板语言不受编辑器支持'));
  }
  if (!isTemplateCategory(template.category)) {
    issues.push(issue('category-unsupported', 'CATEGORY_UNSUPPORTED', 'category', '公共模板类别不受编辑器支持'));
  }

  if (selectedIndex === null) {
    issues.push(issue('page-selection-required', 'PAGE_SELECTION_REQUIRED', 'page', '请选择有效的公共模板页面'));
    return {
      sourceTemplate: template,
      selectedPageIndex: null,
      initialValue: {
        name,
        language,
        category,
        body: '',
        headerFormat: null,
        headerText: '',
        mediaAssetId: null,
        footer: '',
        buttons: [],
        examples: {},
      },
      issues,
      unresolvedButtons: [],
    };
  }

  const page = template.content.pages[selectedIndex];
  const sourceBody = page.text ?? '';
  const { body, unknown } = editorBody(sourceBody, template.content.variables);
  const buttonConversion = convertedButtons(page.buttons, selectedIndex);

  if (!sourceBody.trim()) {
    issues.push(issue('body-missing', 'BODY_MISSING', 'body', '公共模板页面缺少正文'));
  }
  if (body.length > MAX_TEMPLATE_BODY_LENGTH) {
    issues.push(issue('body-too-long', 'BODY_TOO_LONG', 'body', `模板正文不能超过 ${MAX_TEMPLATE_BODY_LENGTH} 个字符`));
  }
  for (const code of unknown) {
    issues.push(issue(`variable-undeclared:${code}`, 'VARIABLE_UNDECLARED', 'variables', `正文引用了未声明变量 ${code}`));
  }
  issues.push(...buttonConversion.issues);

  return {
    sourceTemplate: template,
    selectedPageIndex: selectedIndex,
    initialValue: {
      name,
      language,
      category,
      body,
      headerFormat: null,
      headerText: '',
      mediaAssetId: null,
      footer: '',
      buttons: buttonConversion.buttons,
      examples: examplesForBody(body, template.content.variables),
    },
    issues,
    unresolvedButtons: buttonConversion.unresolvedButtons,
  };
}
