import type { TemplateAdmin, TemplateButton, TemplateCategory, TemplateCommand, TemplateComponent, TemplateHeaderFormat } from '../../api/types';
import { isWhatsAppTemplateLanguage, type WhatsAppTemplateLanguageCode } from './whatsappLanguages';

export const MAX_TEMPLATE_NAME_LENGTH = 512;
export const MAX_TEMPLATE_BODY_LENGTH = 1024;

export interface TemplateEditorInitialValue {
  name: string;
  language: string;
  category: TemplateCategory | null;
  body: string;
  headerFormat: TemplateHeaderFormat | null;
  headerText: string;
  mediaAssetId: string | null;
  footer: string;
  buttons: TemplateButton[];
  examples: Record<string, string[]>;
}

export function isTemplateCategory(value: string | null): value is TemplateCategory {
  return value === 'UTILITY' || value === 'MARKETING';
}

export function isTemplateLanguage(value: string | null): value is WhatsAppTemplateLanguageCode {
  return isWhatsAppTemplateLanguage(value);
}

export const statusLabels: Record<TemplateAdmin['reviewStatus'], string> = {
  PENDING: '审核中',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
  SUSPENDED: '已暂停',
  UNKNOWN: '未知',
};

export const statusColors: Record<TemplateAdmin['reviewStatus'], string> = {
  PENDING: 'processing',
  APPROVED: 'success',
  REJECTED: 'error',
  SUSPENDED: 'warning',
  UNKNOWN: 'default',
};

export function templatePermissionState(template: TemplateAdmin): {
  label: string;
  color: string;
  actionLabel: '暂停发送' | '恢复发送';
  toggleDisabled: boolean;
} {
  const actionLabel = template.desiredAllowSend ? '暂停发送' : '恢复发送';
  const toggleDisabled = template.permissionSyncStatus === 'PENDING'
    || (!template.desiredAllowSend && template.reviewStatus !== 'APPROVED');
  if (template.permissionSyncStatus === 'PENDING') {
    return {
      label: template.desiredAllowSend ? '启用同步中' : '停用同步中',
      color: 'processing',
      actionLabel,
      toggleDisabled,
    };
  }
  if (template.permissionSyncStatus === 'FAILED') {
    return {
      label: template.desiredAllowSend ? '启用失败' : '停用失败',
      color: 'error',
      actionLabel,
      toggleDisabled,
    };
  }
  return {
    label: template.allowSend ? '已启用' : '已暂停',
    color: template.allowSend ? 'green' : 'default',
    actionLabel,
    toggleDisabled,
  };
}

export const headerFormatLabels: Record<TemplateHeaderFormat, string> = {
  TEXT: '文本',
  IMAGE: '图片',
  VIDEO: '视频',
  DOCUMENT: '文件',
};

export function requestId(): string {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `template-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export function bodyComponent(components: TemplateComponent[]): TemplateComponent {
  return components.find((component) => component.type === 'BODY') ?? {
    type: 'BODY', headerFormat: null, text: '', mediaAssetId: null, buttons: [],
  };
}

export function componentForHeader(format: TemplateHeaderFormat, text: string | null, mediaAssetId: string | null): TemplateComponent {
  return { type: 'HEADER', headerFormat: format, text, mediaAssetId, buttons: [] };
}

export function initialValueForTemplate(template: TemplateAdmin | null): TemplateEditorInitialValue {
  const header = template?.components.find((component) => component.type === 'HEADER');
  const body = bodyComponent(template?.components ?? []);
  const footer = template?.components.find((component) => component.type === 'FOOTER');
  const buttonItem = template?.components.find((component) => component.type === 'BUTTONS');
  const bodyText = body.text ?? '';
  const headerFormat = header?.headerFormat ?? null;
  const headerText = header?.text ?? '';
  const variables = [...new Set([
    ...variableNames(bodyText),
    ...(headerFormat === 'TEXT' ? variableNames(headerText) : []),
  ])];
  const examples: Record<string, string[]> = {};
  for (const variable of variables) examples[variable] = template?.examples[variable] ?? [''];

  return {
    name: template?.name ?? '',
    language: template?.language ?? '',
    category: template?.category === 'MARKETING' ? 'MARKETING' : 'UTILITY',
    body: bodyText,
    headerFormat,
    headerText,
    mediaAssetId: header?.mediaAssetId ?? null,
    footer: footer?.text ?? '',
    buttons: buttonItem?.buttons ?? [],
    examples,
  };
}

export function buildCommand(values: {
  name: string;
  language: string;
  category: 'UTILITY' | 'MARKETING';
  body: string;
  headerFormat: TemplateHeaderFormat | null;
  headerText: string;
  mediaAssetId: string | null;
  footer: string;
  buttons: TemplateButton[];
  examples: Record<string, string[]>;
}): TemplateCommand {
  const components: TemplateComponent[] = [{
    type: 'BODY', headerFormat: null, text: values.body, mediaAssetId: null, buttons: [],
  }];
  if (values.headerFormat) {
    components.unshift(componentForHeader(
      values.headerFormat,
      values.headerFormat === 'TEXT' ? values.headerText : null,
      values.mediaAssetId,
    ));
  }
  if (values.footer.trim()) {
    components.push({ type: 'FOOTER', headerFormat: null, text: values.footer, mediaAssetId: null, buttons: [] });
  }
  if (values.buttons.length) {
    components.push({ type: 'BUTTONS', headerFormat: null, text: null, mediaAssetId: null, buttons: values.buttons });
  }
  return {
    name: values.name.trim(),
    language: values.language.trim(),
    category: values.category,
    components,
    examples: values.examples,
    clientRequestId: requestId(),
  };
}

export function variableNames(text: string): string[] {
  const names = new Set<string>();
  for (const match of text.matchAll(/\$\(\s*([A-Za-z][A-Za-z0-9_]*)\s*\)/g)) names.add(match[1]);
  return [...names];
}

export function hasUnsupportedVariableSyntax(text: string): boolean {
  return /\$\{\s*[A-Za-z][A-Za-z0-9_]*\s*}|\{\{\s*[A-Za-z][A-Za-z0-9_]*\s*}}/.test(text);
}
