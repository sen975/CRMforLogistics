import { Button, Descriptions, Empty, Modal, Segmented, Space, Tag, Tooltip, Typography } from 'antd';
import type { SharedTemplate, TemplateOperation } from '../../api/types';
import type { PreviewMode } from './publicTemplatePreview';
import TemplateMessagePreview from './TemplateMessagePreview';
import { formatTemplateTimestamp, sharedTemplatePreview } from './sharedTemplatePreview';
import { statusColors, statusLabels, templatePermissionState } from './templateUi';

export default function SharedTemplateDetailModal({ template, operations, open, previewMode, isAdmin, onPreviewModeChange, onEdit, onToggleSend, onDelete, onClose }: {
  template: SharedTemplate | null; operations: TemplateOperation[]; open: boolean; previewMode: PreviewMode;
  /**
   * 「开启发送」这个动作只有<b>管理员</b>会直接执行（服务端走 DIRECT），而服务端对它的要求是
   * 模板必须已审核通过 ⇒ 未通过时必须禁用按钮，否则用户点下去只会撞上 409 TEMPLATE_NOT_APPROVED。
   * 非管理员走审批流（APPROVAL_REQUIRED），Business App 私有域则走另一条不校验审核状态的路径，
   * 这两种情况都不该禁用 —— 所以判据是 {@code isAdmin && toggleDisabled}，与卡片栅格保持同一口径。
   */
  isAdmin: boolean;
  onPreviewModeChange(mode: PreviewMode): void;
  onEdit(template: SharedTemplate): void; onToggleSend(template: SharedTemplate): void; onDelete(template: SharedTemplate): void; onClose(): void;
}) {
  const preview = template ? sharedTemplatePreview(template) : null;
  const permission = template ? templatePermissionState(template) : null;
  const toggleBlocked = Boolean(isAdmin && permission?.toggleDisabled);
  // 「暂停」对非营销类模板必然失败（CAMS ERR-COMMON-001，2026-09-29 实测），对谁都不该给出口。
  const toggleUnavailable = Boolean(permission?.toggleUnavailable);
  const toggleHint = !template
    ? ''
    : toggleUnavailable
      ? permission?.toggleUnavailableReason ?? ''
      : toggleBlocked
        ? `该模板当前为「${statusLabels[template.reviewStatus]}」，只有审核通过的模板才能开启发送`
        : '';
  return <Modal title="共享模板详情" open={open} width="min(1120px, calc(100vw - 32px))" destroyOnHidden onCancel={onClose} footer={template ? <Space wrap><Button onClick={onClose}>关闭</Button><Tooltip title={toggleHint}><span className="shared-template-detail__toggle"><Button disabled={toggleUnavailable || toggleBlocked} onClick={() => onToggleSend(template)}>{permission?.actionLabel}</Button></span></Tooltip><Button danger onClick={() => onDelete(template)}>删除</Button><Button type="primary" onClick={() => onEdit(template)}>编辑模板</Button></Space> : null}>
    {!template ? <Empty description="没有可显示的模板详情" /> : <div className="public-template-workbench-modal-body"><section className="public-template-detail-fields"><Typography.Title level={4} style={{ marginTop: 0 }}>{template.displayName}</Typography.Title><Descriptions column={1} size="small" bordered><Descriptions.Item label="官方名称">{template.name}</Descriptions.Item><Descriptions.Item label="备注">{template.remark ?? '无'}</Descriptions.Item><Descriptions.Item label="模板代码">{template.templateCode}</Descriptions.Item><Descriptions.Item label="语言">{template.language}</Descriptions.Item><Descriptions.Item label="审核状态"><Tag color={statusColors[template.reviewStatus]}>{statusLabels[template.reviewStatus]}</Tag></Descriptions.Item><Descriptions.Item label="发送状态"><Tag color={permission?.color}>{permission?.label}</Tag></Descriptions.Item><Descriptions.Item label="上次同步">{formatTemplateTimestamp(template.lastSyncedAt)}</Descriptions.Item></Descriptions><section className="shared-template-operations"><Typography.Title level={5}>操作记录</Typography.Title>{operations.length ? operations.map((operation) => <Typography.Paragraph key={operation.operationId}>{operation.operationType}：{operation.operationStatus}{operation.errorMessage ? `，${operation.errorMessage}` : ''}</Typography.Paragraph>) : <Typography.Text type="secondary">暂无操作记录</Typography.Text>}</section></section><section className="public-template-detail-preview"><Segmented aria-label="预览模式" block options={[{ value: 'parameter', label: '参数' }, { value: 'example', label: '示例' }]} value={previewMode} onChange={(value) => onPreviewModeChange(value as PreviewMode)} />{preview && <TemplateMessagePreview page={preview.page} variables={preview.variables} mode={previewMode} />}</section></div>}
  </Modal>;
}
