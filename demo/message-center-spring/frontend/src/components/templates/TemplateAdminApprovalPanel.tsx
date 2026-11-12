import { Alert, Button, Input, List, Space, Tag, Typography } from 'antd';
import type { TemplateChangeRequestView } from '../../api/types';
import { changeStatusLabels } from './TemplateChangeRequestList';
import { templateScopeTypeLabels, templateSpaceLabel } from './templateUi';

export default function TemplateAdminApprovalPanel({ requests, loading, approvingId, retryingId, onOpen, onApprove, onReject, onRetry }: {
  requests: TemplateChangeRequestView[]; loading: boolean; approvingId: string | null; retryingId: string | null; onOpen(request: TemplateChangeRequestView): void;
  onApprove(request: TemplateChangeRequestView): void; onReject(request: TemplateChangeRequestView, reason: string): void; onRetry(request: TemplateChangeRequestView): void;
}) {
  return <List loading={loading} dataSource={requests} locale={{ emptyText: '暂无待处理申请' }} renderItem={(request) => {
    const canApprove = request.status === 'PENDING_APPROVAL';
    const canRetry = request.status === 'EXECUTION_FAILED';
    const space = templateSpaceLabel(request);
    let reason = '';
    return <List.Item><Space direction="vertical" style={{ width: '100%' }}><Space wrap><Typography.Text strong>{request.templateDisplayName}</Typography.Text><Tag>{changeStatusLabels[request.status]}</Tag><Typography.Text type="secondary">申请人：{request.requestedByDisplayName}</Typography.Text></Space><Space wrap><Typography.Text type="secondary">CAMS 空间：{space ?? '未知'}</Typography.Text>{request.providerScopeType ? <Tag color={request.providerScopeType === 'EMPLOYEE_BUSINESS_APP' ? 'blue' : 'default'}>{templateScopeTypeLabels[request.providerScopeType] ?? request.providerScopeType}</Tag> : null}</Space>{request.status === 'STALE' && <Alert type="warning" showIcon message="模板版本已变化，不能批准该申请" />}{request.executionErrorMessage && <Alert type="error" showIcon message={request.executionErrorMessage} />}<Space wrap><Button onClick={() => onOpen(request)}>查看差异</Button><Button type="primary" disabled={!canApprove} loading={approvingId === request.id} onClick={() => onApprove(request)}>批准</Button><Input aria-label={`拒绝理由 ${request.templateDisplayName}`} placeholder="拒绝理由" style={{ width: 220 }} onChange={(event) => { reason = event.target.value; }} /><Button danger disabled={!canApprove} onClick={() => onReject(request, reason)}>拒绝</Button>{canRetry && <Button loading={retryingId === request.id} onClick={() => onRetry(request)}>重试</Button>}</Space></Space></List.Item>;
  }} />;
}
