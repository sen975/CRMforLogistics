import type { PublicTemplatePage, PublicTemplateVariable, SharedTemplate } from '../../api/types';

export function sharedTemplatePreview(template: SharedTemplate): { page: PublicTemplatePage; variables: PublicTemplateVariable[] } {
  const header = template.components.find((component) => component.type === 'HEADER');
  const body = template.components.find((component) => component.type === 'BODY');
  const footer = template.components.find((component) => component.type === 'FOOTER');
  const buttons = template.components.find((component) => component.type === 'BUTTONS');
  return {
    page: {
      name: template.displayName,
      text: [header?.headerFormat === 'TEXT' ? header.text : null, body?.text, footer?.text]
        .filter((value): value is string => Boolean(value?.trim())).join('\n\n'),
      buttons: (buttons?.buttons ?? []).map((button) => ({ name: button.text, type: button.type, url: button.url })),
    },
    variables: Object.entries(template.examples).map(([code, examples]) => ({
      code, name: code, example: examples.find((value) => value.trim()) ?? null, format: 'TEXT',
    })),
  };
}

export function formatTemplateTimestamp(iso: string | null): string {
  if (!iso) return '从未同步';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '未提供' : date.toLocaleString('zh-CN', { hour12: false });
}
