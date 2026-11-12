import type { PublicTemplatePage, PublicTemplateVariable } from '../../api/types';
import { previewSegments, type PreviewMode } from './publicTemplatePreview';
import './publicTemplateWorkbench.css';

interface TemplateMessagePreviewProps {
  page: PublicTemplatePage;
  variables: PublicTemplateVariable[];
  mode: PreviewMode;
  compact?: boolean;
}

function externalHttpUrl(value: string | null): string | null {
  const url = value?.trim();
  if (!url) return null;

  try {
    const parsed = new URL(url);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? url : null;
  } catch {
    return null;
  }
}

export default function TemplateMessagePreview({
  page,
  variables,
  mode,
  compact = false,
}: TemplateMessagePreviewProps) {
  const segments = previewSegments(page.text ?? '', variables, mode);
  // 页脚不解析 $(变量)：平台的 FOOTER 组件本来就不允许变量，
  // 真出现了也不该在这里替它圆成高亮 —— 原样显示才看得见问题。
  const footer = page.footer?.trim();

  return (
    <div className={`template-message-canvas${compact ? ' template-message-canvas--compact' : ''}`}>
      <article className="template-message-bubble" aria-label="模板消息预览">
        <div className="template-message-text">
          {segments.map((segment, index) => segment.kind === 'text' || !segment.resolved
            ? <span key={index}>{segment.value}</span>
            : <span key={`${segment.code}-${index}`} className="template-variable">{segment.value}</span>)}
        </div>
        {footer && <div className="template-message-footer">{footer}</div>}
        {page.buttons.length > 0 && (
          <div className="template-message-actions">
            {page.buttons.map((button, index) => {
              const url = externalHttpUrl(button.url);
              const label = button.name?.trim() || '未命名按钮';

              return !compact && url
                ? <a key={index} className="template-message-action" href={url} target="_blank" rel="noreferrer">{label}</a>
                : <span key={index} className="template-message-action">{label}</span>;
            })}
          </div>
        )}
      </article>
    </div>
  );
}
