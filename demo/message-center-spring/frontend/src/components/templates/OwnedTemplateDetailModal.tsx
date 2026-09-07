import { Alert, Button, Descriptions, Empty, List, Modal, Segmented, Space, Tag, Typography } from 'antd';
import type { TemplateAdmin, TemplateOperation } from '../../api/types';
import type { PreviewMode } from './publicTemplatePreview';
import TemplateMessagePreview from './TemplateMessagePreview';
import { formatTemplateTimestamp, ownedTemplatePreview } from './ownedTemplatePreview';
import { statusColors, statusLabels, templatePermissionState } from './templateUi';

const { Text, Title } = Typography;

function categoryLabel(category: string | null): string {
  if (category === 'UTILITY') return '工具';
  if (category === 'MARKETING') return '营销';
  return category ?? '未提供';
}

function operationTypeLabel(operationType: TemplateOperation['operationType']): string {
  return operationType === 'RETIRED' ? '旧路径已退役' : operationType;
}

export default function OwnedTemplateDetailModal({
  template,
  operations,
  operationsLoading,
  operationsError,
  loading,
  open,
  previewMode,
  onPreviewModeChange,
  onEdit,
  onRemark,
  onToggleSend,
  onDelete,
  onClose,
}: {
  template: TemplateAdmin | null;
  operations: TemplateOperation[];
  operationsLoading: boolean;
  operationsError: string | null;
  loading: boolean;
  open: boolean;
  previewMode: PreviewMode;
  onPreviewModeChange(mode: PreviewMode): void;
  onEdit(template: TemplateAdmin): void;
  onRemark(template: TemplateAdmin): void;
  onToggleSend(template: TemplateAdmin): void;
  onDelete(template: TemplateAdmin): void;
  onClose(): void;
}) {
  const preview = template ? ownedTemplatePreview(template) : null;
  const permission = template ? templatePermissionState(template) : null;

  return (
    <Modal
      title="模板详情"
      open={open}
      width="min(1120px, calc(100vw - 32px))"
      destroyOnHidden
      onCancel={onClose}
      footer={template ? (
        <Space wrap>
          <Button onClick={onClose}>关闭</Button>
          <Button onClick={() => onRemark(template)}>编辑备注</Button>
          <Button disabled={permission?.toggleDisabled} onClick={() => onToggleSend(template)}>{permission?.actionLabel}</Button>
          <Button danger onClick={() => onDelete(template)}>删除</Button>
          <Button type="primary" onClick={() => onEdit(template)}>编辑模板</Button>
        </Space>
      ) : null}
    >
      {!template ? <Empty description="没有可显示的模板详情" /> : (
        <div className="public-template-workbench-modal-body">
          <section className="public-template-detail-fields">
            <Title level={4} style={{ marginTop: 0 }}>{template.displayName}</Title>
            {template.rejectionReason && <Alert type="error" showIcon message="拒绝原因" description={template.rejectionReason} style={{ margin: '16px 0' }} />}
            {template.permissionSyncStatus === 'FAILED' && template.permissionSyncError
              && <Alert type="error" showIcon message="发送权限同步失败" description={template.permissionSyncError} style={{ margin: '16px 0' }} />}
            <Descriptions column={1} size="small" bordered style={{ marginTop: 16 }}>
              <Descriptions.Item label="官方名称">{template.name}</Descriptions.Item>
              <Descriptions.Item label="备注">{template.remark ?? '无'}</Descriptions.Item>
              <Descriptions.Item label="模板代码">{template.templateCode}</Descriptions.Item>
              <Descriptions.Item label="语言">{template.language}</Descriptions.Item>
              <Descriptions.Item label="类别">{categoryLabel(template.category)}</Descriptions.Item>
              <Descriptions.Item label="审核状态"><Tag color={statusColors[template.reviewStatus]}>{statusLabels[template.reviewStatus]}</Tag></Descriptions.Item>
              <Descriptions.Item label="原始审核状态">{template.providerAuditStatus ?? '未提供'}</Descriptions.Item>
              <Descriptions.Item label="发送状态"><Tag color={permission?.color}>{permission?.label}</Tag></Descriptions.Item>
              <Descriptions.Item label="质量">{template.qualityScore ?? '未提供'}</Descriptions.Item>
              <Descriptions.Item label="发送 TTL">{template.messageSendTtlSeconds ?? '未提供'}</Descriptions.Item>
              <Descriptions.Item label="上次同步">{formatTemplateTimestamp(template.lastSyncedAt)}</Descriptions.Item>
            </Descriptions>
            <section className="owned-template-operations">
              <Title level={5}>操作记录</Title>
              {loading || operationsLoading
                ? <Text type="secondary">正在加载操作记录</Text>
                : operationsError
                  ? <Alert type="error" showIcon message={operationsError} />
                  : operations.length === 0
                    ? <Text type="secondary">暂无操作记录</Text>
                    : <List size="small" dataSource={operations} renderItem={(operation) => (
                      <List.Item>
                        <Space direction="vertical" size={0}>
                          <Text>{operationTypeLabel(operation.operationType)}</Text>
                          <Text type="secondary">{operation.operationStatus}{operation.errorMessage ? `：${operation.errorMessage}` : ''}</Text>
                        </Space>
                      </List.Item>
                    )} />}
            </section>
          </section>
          <section className="public-template-detail-preview">
            <Segmented
              aria-label="预览模式"
              block
              options={[{ value: 'parameter', label: '参数' }, { value: 'example', label: '示例' }]}
              value={previewMode}
              onChange={(value) => onPreviewModeChange(value as PreviewMode)}
            />
            {preview && <TemplateMessagePreview page={preview.page} variables={preview.variables} mode={previewMode} />}
          </section>
        </div>
      )}
    </Modal>
  );
}
