import { useState } from 'react';
import { Alert, App, Input, Pagination, Select, Space, Typography } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { approveTemplateChangeRequest, fetchTemplateChangeRequestsForReview, rejectTemplateChangeRequest, retryTemplateChangeRequest } from '../api/endpoints';
import type { TemplateChangeRequestView } from '../api/types';
import TemplateAdminApprovalPanel from '../components/templates/TemplateAdminApprovalPanel';
import TemplateChangeDiffModal from '../components/templates/TemplateChangeDiffModal';
import { requestId } from '../components/templates/templateUi';

const { Title, Text } = Typography;

export default function AdminWhatsAppTemplateApprovalsPage() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState<string>();
  const [search, setSearch] = useState('');
  const [diffRequest, setDiffRequest] = useState<TemplateChangeRequestView | null>(null);
  const requests = useQuery({ queryKey: ['template-change-requests', 'review', page, status ?? null, search.trim()], queryFn: () => fetchTemplateChangeRequestsForReview(page, 20, { status, search }), retry: false });
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['template-change-requests', 'review'] });
  const approve = useMutation({ mutationFn: (request: TemplateChangeRequestView) => approveTemplateChangeRequest(request.id, requestId()), onSuccess: () => { message.success('已批准，正在执行'); refresh(); }, onError: () => message.error('批准失败，请刷新后重试') });
  const reject = useMutation({ mutationFn: ({ request, reason }: { request: TemplateChangeRequestView; reason: string }) => rejectTemplateChangeRequest(request.id, reason), onSuccess: () => { message.success('已拒绝申请'); refresh(); }, onError: () => message.error('拒绝失败，请刷新后重试') });
  const retry = useMutation({ mutationFn: (request: TemplateChangeRequestView) => retryTemplateChangeRequest(request.id, requestId()), onSuccess: () => { message.success('已重新执行'); refresh(); }, onError: () => message.error('重试失败，请刷新后重试') });
  return <div style={{ maxWidth: 1180, margin: '0 auto', padding: 24 }}><Title level={3}>WhatsApp 模板审批</Title><Text type="secondary">审核共享模板变更，执行结果会影响同一 CAMS 空间的用户</Text><Space wrap style={{ margin: '20px 0' }}><Input aria-label="搜索申请" placeholder="模板或申请人" value={search} onChange={(event) => { setSearch(event.target.value); setPage(1); }} style={{ width: 240 }} /><Select aria-label="审批状态" allowClear placeholder="审批状态" value={status} onChange={(next) => { setStatus(next); setPage(1); }} options={[{ value: 'PENDING_APPROVAL', label: '待审批' }, { value: 'EXECUTING', label: '执行中' }, { value: 'EXECUTION_FAILED', label: '执行失败' }, { value: 'SUCCEEDED', label: '已完成' }, { value: 'REJECTED', label: '已拒绝' }, { value: 'STALE', label: '版本已变化' }]} style={{ width: 150 }} /></Space>{requests.isError ? <Alert type="error" showIcon message="审批队列加载失败" /> : null}<TemplateAdminApprovalPanel loading={requests.isLoading} requests={requests.data?.items ?? []} approvingId={approve.variables?.id ?? null} retryingId={retry.variables?.id ?? null} onOpen={setDiffRequest} onApprove={(request) => approve.mutate(request)} onReject={(request, reason) => { if (!reason.trim()) { message.error('拒绝理由不能为空'); return; } reject.mutate({ request, reason }); }} onRetry={(request) => retry.mutate(request)} /><Pagination style={{ marginTop: 16 }} current={requests.data?.page ?? page} pageSize={requests.data?.size ?? 20} total={requests.data?.total ?? 0} showSizeChanger={false} onChange={setPage} /><TemplateChangeDiffModal request={diffRequest} open={Boolean(diffRequest)} onClose={() => setDiffRequest(null)} /></div>;
}
