import { Empty, Skeleton } from 'antd';
import type { PublicTemplate } from '../../api/types';
import PublicTemplateCard from './PublicTemplateCard';

export default function PublicTemplateCardGrid({
  templates,
  loading,
  emptyDescription,
  onOpen,
}: {
  templates: PublicTemplate[];
  loading: boolean;
  emptyDescription: string;
  onOpen: (template: PublicTemplate) => void;
}) {
  if (loading) {
    return (
      <div className="public-template-grid" aria-label="正在加载公共模板">
        {Array.from({ length: 6 }, (_value, index) => (
          <div key={index} className="public-template-card-skeleton"><Skeleton active paragraph={{ rows: 5 }} /></div>
        ))}
      </div>
    );
  }

  if (templates.length === 0) return <Empty description={emptyDescription} />;

  return (
    <div className="public-template-grid">
      {templates.map((template) => <PublicTemplateCard key={template.code} template={template} onOpen={onOpen} />)}
    </div>
  );
}
