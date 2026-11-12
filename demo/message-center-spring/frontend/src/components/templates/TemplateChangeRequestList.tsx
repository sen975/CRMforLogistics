import { Button, Empty, List, Space, Tag, Typography } from 'antd';
import type { TemplateChangeRequestView } from '../../api/types';

export const changeStatusLabels: Record<TemplateChangeRequestView['status'], string> = {
  PENDING_APPROVAL: '等待审批', REJECTED: '已拒绝', STALE: '模板版本已变化', EXECUTING: '已批准正在执行', SUCCEEDED: '执行成功', EXECUTION_FAILED: '执行失败',
};

export default function TemplateChangeRequestList({ requests, loading, onOpen }: { requests: TemplateChangeRequestView[]; loading: boolean; onOpen(request: TemplateChangeRequestView): void }) {
  if (!requests.length && !loading) return <Empty description="暂无申请" />;
  return <List loading={loading} dataSource={requests} renderItem={(request) => <List.Item actions={[<Button key="detail" type="link" onClick={() => onOpen(request)}>查看差异</Button>]}><List.Item.Meta title={<Space wrap><Typography.Text strong>{request.templateDisplayName}</Typography.Text><Tag>{changeStatusLabels[request.status]}</Tag></Space>} description={request.reviewReason ?? request.executionErrorMessage ?? request.changeType} /></List.Item>} />;
}
