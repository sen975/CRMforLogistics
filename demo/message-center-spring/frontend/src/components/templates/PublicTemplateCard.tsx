import { Space, Tag, Typography } from 'antd';
import type { PublicTemplate, PublicTemplatePage } from '../../api/types';
import TemplateMessagePreview from './TemplateMessagePreview';

const { Text } = Typography;

function categoryLabel(category: string | null): string {
  if (category === 'UTILITY') return '工具';
  if (category === 'MARKETING') return '营销';
  return category ?? '未提供';
}

export function firstPreviewablePage(template: PublicTemplate): PublicTemplatePage {
  return template.content.pages.find((page) => page.text?.trim())
    ?? template.content.pages[0]
    ?? { name: null, text: null, buttons: [] };
}

export default function PublicTemplateCard({ template, onOpen }: {
  template: PublicTemplate;
  onOpen: (template: PublicTemplate) => void;
}) {
  return (
    <button
      type="button"
      className="public-template-card"
      aria-label={`查看公共模板 ${template.code}`}
      onClick={() => onOpen(template)}
    >
      <TemplateMessagePreview
        page={firstPreviewablePage(template)}
        variables={template.content.variables}
        mode="parameter"
        compact
      />
      <footer className="public-template-card__footer">
        <Text strong ellipsis={{ tooltip: template.name }}>{template.name}</Text>
        <Space size={4} wrap>
          <Tag>{template.language}</Tag>
          <Tag>{categoryLabel(template.category)}</Tag>
        </Space>
      </footer>
    </button>
  );
}
