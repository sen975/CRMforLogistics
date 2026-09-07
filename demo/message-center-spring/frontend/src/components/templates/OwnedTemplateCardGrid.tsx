import { Button, Empty, Skeleton, Space, Tag, Tooltip, Typography } from 'antd';
import { CommentOutlined, DeleteOutlined, EditOutlined, EyeOutlined, PauseCircleOutlined, PlayCircleOutlined } from '@ant-design/icons';
import type { TemplateAdmin } from '../../api/types';
import TemplateMessagePreview from './TemplateMessagePreview';
import { formatTemplateTimestamp, ownedTemplatePreview } from './ownedTemplatePreview';
import { statusColors, statusLabels, templatePermissionState } from './templateUi';

const { Text } = Typography;

function categoryLabel(category: string | null): string {
  if (category === 'UTILITY') return '工具';
  if (category === 'MARKETING') return '营销';
  return category ?? '未提供';
}

function OwnedTemplateCard({
  template,
  remarkPending,
  onOpen,
  onEdit,
  onRemark,
  onToggleSend,
  onDelete,
}: {
  template: TemplateAdmin;
  remarkPending: boolean;
  onOpen(template: TemplateAdmin): void;
  onEdit(template: TemplateAdmin): void;
  onRemark(template: TemplateAdmin): void;
  onToggleSend(template: TemplateAdmin): void;
  onDelete(template: TemplateAdmin): void;
}) {
  const preview = ownedTemplatePreview(template);
  const permission = templatePermissionState(template);

  return (
    <article className="owned-template-card">
      <button
        type="button"
        className="owned-template-card__preview"
        aria-label={`查看我的模板 ${template.templateCode}`}
        onClick={() => onOpen(template)}
      >
        <TemplateMessagePreview page={preview.page} variables={preview.variables} mode="parameter" compact />
        <div className="owned-template-card__summary">
          <Text strong ellipsis={{ tooltip: template.displayName }}>{template.displayName}</Text>
          {template.remark && <Text type="secondary" ellipsis={{ tooltip: template.name }}>官方名称：{template.name}</Text>}
          <Space size={4} wrap>
            <Tag>{template.language}</Tag>
            <Tag>{categoryLabel(template.category)}</Tag>
            <Tag color={statusColors[template.reviewStatus]}>{statusLabels[template.reviewStatus]}</Tag>
            <Tag color={permission.color}>{permission.label}</Tag>
          </Space>
          {template.permissionSyncStatus === 'FAILED' && template.permissionSyncError
            && <Text type="danger" ellipsis={{ tooltip: template.permissionSyncError }}>{template.permissionSyncError}</Text>}
          <Text type="secondary">{template.templateCode}</Text>
          <Text type="secondary">{formatTemplateTimestamp(template.lastSyncedAt)}</Text>
        </div>
      </button>
      <footer className="owned-template-card__actions">
        <Tooltip title="查看详情"><Button type="text" aria-label={`查看 ${template.templateCode}`} icon={<EyeOutlined />} onClick={() => onOpen(template)} /></Tooltip>
        <Tooltip title="编辑"><Button type="text" aria-label={`编辑 ${template.templateCode}`} icon={<EditOutlined />} onClick={() => onEdit(template)} /></Tooltip>
        <Tooltip title="编辑备注"><Button type="text" aria-label={`编辑备注 ${template.templateCode}`} disabled={remarkPending} icon={<CommentOutlined />} onClick={() => onRemark(template)} /></Tooltip>
        <Tooltip title={permission.actionLabel}><Button type="text" aria-label={`${permission.actionLabel} ${template.templateCode}`} disabled={permission.toggleDisabled} icon={template.desiredAllowSend ? <PauseCircleOutlined /> : <PlayCircleOutlined />} onClick={() => onToggleSend(template)} /></Tooltip>
        <Tooltip title="删除"><Button type="text" danger aria-label={`删除 ${template.templateCode}`} icon={<DeleteOutlined />} onClick={() => onDelete(template)} /></Tooltip>
      </footer>
    </article>
  );
}

export default function OwnedTemplateCardGrid({
  templates,
  loading,
  emptyDescription,
  remarkPending,
  onOpen,
  onEdit,
  onRemark,
  onToggleSend,
  onDelete,
}: {
  templates: TemplateAdmin[];
  loading: boolean;
  emptyDescription: string;
  remarkPending: boolean;
  onOpen(template: TemplateAdmin): void;
  onEdit(template: TemplateAdmin): void;
  onRemark(template: TemplateAdmin): void;
  onToggleSend(template: TemplateAdmin): void;
  onDelete(template: TemplateAdmin): void;
}) {
  if (loading) {
    return (
      <div className="public-template-grid" aria-label="正在加载我的模板">
        {Array.from({ length: 6 }, (_value, index) => (
          <div key={index} className="public-template-card-skeleton"><Skeleton active paragraph={{ rows: 6 }} /></div>
        ))}
      </div>
    );
  }

  if (templates.length === 0) return <Empty description={emptyDescription} />;

  return (
    <div className="public-template-grid">
      {templates.map((template) => (
        <OwnedTemplateCard
          key={`${template.templateCode}:${template.language}`}
          template={template}
          remarkPending={remarkPending}
          onOpen={onOpen}
          onEdit={onEdit}
          onRemark={onRemark}
          onToggleSend={onToggleSend}
          onDelete={onDelete}
        />
      ))}
    </div>
  );
}
