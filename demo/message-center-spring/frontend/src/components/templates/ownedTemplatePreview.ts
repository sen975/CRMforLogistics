import type { PublicTemplatePage, PublicTemplateVariable, TemplateAdmin } from '../../api/types';

export interface OwnedTemplatePreview {
  page: PublicTemplatePage;
  variables: PublicTemplateVariable[];
}

export function ownedTemplatePreview(template: TemplateAdmin): OwnedTemplatePreview {
  const header = template.components.find((component) => component.type === 'HEADER');
  const body = template.components.find((component) => component.type === 'BODY');
  const footer = template.components.find((component) => component.type === 'FOOTER');
  const buttons = template.components.find((component) => component.type === 'BUTTONS');
  const text = [
    header?.headerFormat === 'TEXT' ? header.text : null,
    body?.text,
  ].filter((value): value is string => Boolean(value?.trim())).join('\n\n');

  return {
    page: {
      name: template.displayName,
      text,
      // 页脚单独带出去、不并进 text —— 并进去它就和正文长得一模一样，
      // 用户会读成「正文里多了一句无关的话」。它在客户手机上是灰色小字。
      footer: footer?.text ?? null,
      buttons: (buttons?.buttons ?? []).map((button) => ({
        name: button.text,
        type: button.type,
        url: button.url,
      })),
    },
    variables: Object.entries(template.examples).map(([code, examples]) => ({
      code,
      name: code,
      example: examples.find((value) => value.trim()) ?? null,
      format: 'TEXT',
    })),
  };
}

export function formatTemplateTimestamp(iso: string | null): string {
  if (!iso) return '从未同步';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '未提供';
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}`;
}
