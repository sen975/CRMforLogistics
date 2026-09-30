import { Button, Empty, Skeleton, Space, Tag, Tooltip, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, EyeOutlined, PauseCircleOutlined, PlayCircleOutlined } from '@ant-design/icons';
import type { SharedTemplate } from '../../api/types';
import TemplateMessagePreview from './TemplateMessagePreview';
import { sharedTemplatePreview } from './sharedTemplatePreview';
import { statusColors, statusLabels, templatePermissionState } from './templateUi';

const { Text } = Typography;

function SharedTemplateCard({ template, isAdmin, onOpen, onEdit, onToggleSend, onDelete }: {
  template: SharedTemplate; isAdmin: boolean; onOpen(template: SharedTemplate): void; onEdit(template: SharedTemplate): void;
  onToggleSend(template: SharedTemplate): void; onDelete(template: SharedTemplate): void;
}) {
  const preview = sharedTemplatePreview(template);
  const permission = templatePermissionState(template);
  const commandLabel = isAdmin ? '直接修改' : '提交审批';
  return <article className="shared-template-card">
    <button type="button" className="shared-template-card__preview" aria-label={`查看 ${template.templateCode}`} onClick={() => onOpen(template)}>
      <TemplateMessagePreview page={preview.page} variables={preview.variables} mode="parameter" compact />
      <div className="shared-template-card__summary">
        <Text strong ellipsis={{ tooltip: template.displayName }}>{template.displayName}</Text>
        <Space size={4} wrap><Tag>{template.language}</Tag><Tag color={statusColors[template.reviewStatus]}>{statusLabels[template.reviewStatus]}</Tag><Tag color={permission.color}>{permission.label}</Tag></Space>
        <Text type="secondary">{template.templateCode}</Text>
      </div>
    </button>
    <footer className="shared-template-card__actions">
      <Tooltip title="查看详情"><Button type="text" aria-label={`查看 ${template.templateCode}`} icon={<EyeOutlined />} onClick={() => onOpen(template)} /></Tooltip>
      <Tooltip title={commandLabel}><Button type="text" aria-label={`编辑 ${template.templateCode}`} icon={<EditOutlined />} onClick={() => onEdit(template)} /></Tooltip>
      <Tooltip title={permission.toggleUnavailable ? permission.toggleUnavailableReason ?? '' : isAdmin ? permission.actionLabel : '提交发送权限审批'}><span className="shared-template-card__toggle"><Button type="text" aria-label={`${permission.actionLabel} ${template.templateCode}`} disabled={permission.toggleUnavailable || (isAdmin && permission.toggleDisabled)} icon={template.allowSend ? <PauseCircleOutlined /> : <PlayCircleOutlined />} onClick={() => onToggleSend(template)} /></span></Tooltip>
      <Tooltip title={isAdmin ? '直接删除' : '提交删除审批'}><Button type="text" danger aria-label={`删除 ${template.templateCode}`} icon={<DeleteOutlined />} onClick={() => onDelete(template)} /></Tooltip>
    </footer>
  </article>;
}

export default function SharedTemplateCardGrid({ templates, loading, emptyDescription, isAdmin, onOpen, onEdit, onToggleSend, onDelete }: {
  templates: SharedTemplate[]; loading: boolean; emptyDescription: string; isAdmin: boolean; onOpen(template: SharedTemplate): void;
  onEdit(template: SharedTemplate): void; onToggleSend(template: SharedTemplate): void; onDelete(template: SharedTemplate): void;
}) {
  if (loading) return <div className="public-template-grid" aria-label="正在加载共享模板">{Array.from({ length: 6 }, (_, index) => <div key={index} className="public-template-card-skeleton"><Skeleton active paragraph={{ rows: 6 }} /></div>)}</div>;
  if (!templates.length) return <Empty description={emptyDescription} />;
  return <div className="public-template-grid">{templates.map((template) => <SharedTemplateCard key={template.id} template={template} isAdmin={isAdmin} onOpen={onOpen} onEdit={onEdit} onToggleSend={onToggleSend} onDelete={onDelete} />)}</div>;
}
