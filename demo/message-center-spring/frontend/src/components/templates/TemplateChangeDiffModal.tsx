import { Descriptions, Empty, Modal, Tag } from 'antd';
import type { TemplateChangeRequestView } from '../../api/types';
import { templateScopeTypeLabels, templateSpaceLabel } from './templateUi';

function display(value: unknown): string {
  if (value === null || value === undefined || value === '') return '未设置';
  if (typeof value === 'boolean') return value ? '启用' : '停用';
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(display).join('、');
  return '结构化内容已变更';
}

export default function TemplateChangeDiffModal({ request, open, onClose }: { request: TemplateChangeRequestView | null; open: boolean; onClose(): void }) {
  return <Modal title="变更详情" open={open} onCancel={onClose} footer={null} destroyOnHidden>
    {!request ? <Empty description="没有可显示的变更" /> : <Descriptions column={1} size="small" bordered>
      <Descriptions.Item label="CAMS 空间">
        {templateSpaceLabel(request) ?? '未知'}
        {request.providerScopeType ? <Tag style={{ marginInlineStart: 8 }} color={request.providerScopeType === 'EMPLOYEE_BUSINESS_APP' ? 'blue' : 'default'}>{templateScopeTypeLabels[request.providerScopeType] ?? request.providerScopeType}</Tag> : null}
      </Descriptions.Item>
      {request.diffs.map((diff) => <Descriptions.Item key={diff.field} label={diff.label}><Tag color="default">变更前：{display(diff.beforeValue)}</Tag><Tag color="processing">变更后：{display(diff.afterValue)}</Tag></Descriptions.Item>)}
    </Descriptions>}
  </Modal>;
}
