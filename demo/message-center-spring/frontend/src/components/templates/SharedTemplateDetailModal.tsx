import { Button, Descriptions, Empty, Modal, Segmented, Space, Tag, Typography } from 'antd';
import type { SharedTemplate, TemplateOperation } from '../../api/types';
import type { PreviewMode } from './publicTemplatePreview';
import TemplateMessagePreview from './TemplateMessagePreview';
import { formatTemplateTimestamp, sharedTemplatePreview } from './sharedTemplatePreview';
import { statusColors, statusLabels, templatePermissionState } from './templateUi';

export default function SharedTemplateDetailModal({ template, operations, open, previewMode, onPreviewModeChange, onEdit, onToggleSend, onDelete, onClose }: {
  template: SharedTemplate | null; operations: TemplateOperation[]; open: boolean; previewMode: PreviewMode; onPreviewModeChange(mode: PreviewMode): void;
  onEdit(template: SharedTemplate): void; onToggleSend(template: SharedTemplate): void; onDelete(template: SharedTemplate): void; onClose(): void;
}) {
  const preview = template ? sharedTemplatePreview(template) : null;
  const permission = template ? templatePermissionState(template) : null;
  return <Modal title="共享模板详情" open={open} width="min(1120px, calc(100vw - 32px))" destroyOnHidden onCancel={onClose} footer={template ? <Space wrap><Button onClick={onClose}>关闭</Button><Button onClick={() => onToggleSend(template)}>{permission?.actionLabel}</Button><Button danger onClick={() => onDelete(template)}>删除</Button><Button type="primary" onClick={() => onEdit(template)}>编辑模板</Button></Space> : null}>
    {!template ? <Empty description="没有可显示的模板详情" /> : <div className="public-template-workbench-modal-body"><section className="public-template-detail-fields"><Typography.Title level={4} style={{ marginTop: 0 }}>{template.displayName}</Typography.Title><Descriptions column={1} size="small" bordered><Descriptions.Item label="官方名称">{template.name}</Descriptions.Item><Descriptions.Item label="备注">{template.remark ?? '无'}</Descriptions.Item><Descriptions.Item label="模板代码">{template.templateCode}</Descriptions.Item><Descriptions.Item label="语言">{template.language}</Descriptions.Item><Descriptions.Item label="审核状态"><Tag color={statusColors[template.reviewStatus]}>{statusLabels[template.reviewStatus]}</Tag></Descriptions.Item><Descriptions.Item label="发送状态"><Tag color={permission?.color}>{permission?.label}</Tag></Descriptions.Item><Descriptions.Item label="上次同步">{formatTemplateTimestamp(template.lastSyncedAt)}</Descriptions.Item></Descriptions><section className="shared-template-operations"><Typography.Title level={5}>操作记录</Typography.Title>{operations.length ? operations.map((operation) => <Typography.Paragraph key={operation.operationId}>{operation.operationType}：{operation.operationStatus}{operation.errorMessage ? `，${operation.errorMessage}` : ''}</Typography.Paragraph>) : <Typography.Text type="secondary">暂无操作记录</Typography.Text>}</section></section><section className="public-template-detail-preview"><Segmented aria-label="预览模式" block options={[{ value: 'parameter', label: '参数' }, { value: 'example', label: '示例' }]} value={previewMode} onChange={(value) => onPreviewModeChange(value as PreviewMode)} />{preview && <TemplateMessagePreview page={preview.page} variables={preview.variables} mode={previewMode} />}</section></div>}
  </Modal>;
}
