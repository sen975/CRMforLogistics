import type { TemplateAdmin, TemplateButton, TemplateCommand, TemplateComponent, TemplateHeaderFormat } from '../../api/types';

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
  for (const match of text.matchAll(/{{\s*([a-zA-Z0-9_]+)\s*}}/g)) names.add(match[1]);
  return [...names];
}

