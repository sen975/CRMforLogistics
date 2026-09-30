import type { SharedTemplate, TemplateButton, TemplateCategory, TemplateCommand, TemplateComponent, TemplateHeaderFormat } from '../../api/types';
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

export const statusLabels: Record<SharedTemplate['reviewStatus'], string> = {
  PENDING: '审核中',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
  SUSPENDED: '已暂停',
  UNKNOWN: '未知',
};

export const statusColors: Record<SharedTemplate['reviewStatus'], string> = {
  PENDING: 'processing',
  APPROVED: 'success',
  REJECTED: 'error',
  SUSPENDED: 'warning',
  UNKNOWN: 'default',
};

export function templatePermissionState(template: SharedTemplate): {
  label: string;
  color: string;
  actionLabel: '暂停发送' | '恢复发送';
  toggleDisabled: boolean;
  /**
   * 「暂停」这个动作对一个非营销类模板根本不成立，按钮必须不可点，并且要把原因说出来。
   *
   * 2026-09-29 实测：同一个通知类（UTILITY）模板、同一把空间凭据，`allowSend=true` 成功，
   * `allowSend=false` 必然拿到 CAMS 的 `ERR-COMMON-001`（`code: 400, System error`），
   * 服务端把它包成 502 抛出来，用户看到的是一串英文加 UUID。Meta 侧也没有业务可控的暂停接口
   * —— 模板的 PAUSED 状态是 Meta 按质量评分自己给的。所以这是个必然失败的动作，不该给出口。
   */
  toggleUnavailable: boolean;
  toggleUnavailableReason: string | null;
} {
  const actionLabel = template.allowSend ? '暂停发送' : '恢复发送';
  const toggleDisabled = !template.allowSend && template.reviewStatus !== 'APPROVED';
  const toggleUnavailable = template.allowSend && template.category !== 'MARKETING';
  return {
    label: template.allowSend ? '已启用' : '已暂停',
    color: template.allowSend ? 'green' : 'default',
    actionLabel,
    toggleDisabled,
    toggleUnavailable,
    toggleUnavailableReason: toggleUnavailable
      ? 'WhatsApp 只允许暂停营销模板：此模板不是营销类，发送状态由 Meta 按质量评分自动管理'
      : null,
  };
}

export const templateScopeTypeLabels: Record<string, string> = {
  ENTERPRISE_API: '企业 API',
  EMPLOYEE_BUSINESS_APP: 'Business App 共存',
};

/**
 * Names the CAMS space a change request touches. Reviewers need it because each space owns its own
 * library and a Business App space is not the same operation as the enterprise API space.
 */
export function templateSpaceLabel(scope: {
  providerScopeId: string | null;
  providerScopeName: string | null;
  providerScopeExternalId: string | null;
}): string | null {
  if (!scope.providerScopeId) return null;
  const name = scope.providerScopeName || scope.providerScopeId;
  return scope.providerScopeExternalId ? `${name}（${scope.providerScopeExternalId}）` : name;
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

export function initialValueForTemplate(template: SharedTemplate | null): TemplateEditorInitialValue {
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

/**
 * 变量是不是落在正文的开头或结尾。
 *
 * 平台会因此直接拒审（拒审原话 Variables can't be at the start or end of the template，
 * 拒审码 Leading or Trailing Params Not Allowed），后端 WhatsAppTemplateValidator 按同一口径拦。
 * 两处判据必须一致：前端放过去、后端再拒，用户看到的是「点了提交什么也没发生」。
 *
 * 先 trim 再判：「 $(name) 您好」这种垫一个空格的写法意图仍是让变量打头，从严。
 * 「两个变量相邻」那条平台口径未定（一处写作拒审、一处写作建议），与后端一样不在这里判。
 */
export function hasLeadingOrTrailingVariable(text: string): boolean {
  const stripped = text.trim();
  if (!stripped) return false;
  return /^\$\(\s*[A-Za-z][A-Za-z0-9_]*\s*\)/.test(stripped)
    || /\$\(\s*[A-Za-z][A-Za-z0-9_]*\s*\)$/.test(stripped);
}

/** 文本里有没有一个 $(name) 形态的变量。页脚一个都不能有：平台的 FOOTER 组件不支持参数。 */
export function hasVariable(text: string): boolean {
  return variableNames(text).length > 0;
}
