import { useState } from 'react';
import { Alert, App, Button, Input, Modal, Pagination, Select, Space, Tabs, Typography } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { approveTemplateChangeRequest, createSharedTemplate, createTemplateChangeRequest, fetchChannelAccounts, fetchMyTemplateChangeRequests, fetchSharedTemplate, fetchSharedTemplates, fetchTemplateChangeRequestsForReview, fetchTemplateMediaUpload, fetchTemplateOperations, rejectTemplateChangeRequest, retryTemplateChangeRequest, syncSharedTemplates, uploadTemplateMedia } from '../api/endpoints';
import type { SharedTemplate, SharedTemplateListFilters, TemplateChangeRequestView, TemplateMediaAsset } from '../api/types';
import { useAuth } from '../hooks/useAuth';
import PublicTemplateLibrary from '../components/templates/PublicTemplateLibrary';
import SharedTemplateCardGrid from '../components/templates/SharedTemplateCardGrid';
import SharedTemplateDetailModal from '../components/templates/SharedTemplateDetailModal';
import TemplateAdminApprovalPanel from '../components/templates/TemplateAdminApprovalPanel';
import TemplateChangeDiffModal from '../components/templates/TemplateChangeDiffModal';
import TemplateChangeRequestList from '../components/templates/TemplateChangeRequestList';
import TemplateEditorDrawer from '../components/templates/TemplateEditorDrawer';
import type { PublicTemplateConversionResult } from '../components/templates/publicTemplateConversion';
import type { PreviewMode } from '../components/templates/publicTemplatePreview';
import { recoverTemplateMediaUpload } from '../components/templates/templateMediaUpload';
import { requestId } from '../components/templates/templateUi';
import { WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS } from '../components/templates/whatsappLanguages';

const { Title } = Typography;
function errorMessage(error: unknown): string { return (error as { response?: { data?: { message?: string } } }).response?.data?.message ?? '操作失败，请稍后重试'; }
function isActiveWhatsAppAccount(account: { channelType: string; authStatus: string }): boolean { return (account.channelType === 'whatsapp' || account.channelType === 'chatapp') && account.authStatus.toLowerCase() === 'active'; }
type PendingAction = { type: 'permission' | 'delete'; template: SharedTemplate; allowSend?: boolean } | null;

export default function TemplatesPage() {
  const { message } = App.useApp();
  const { isAdmin } = useAuth();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<SharedTemplateListFilters>({ page: 1, size: 20 });
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<SharedTemplate | null>(null);
  const [publicDraft, setPublicDraft] = useState<PublicTemplateConversionResult | null>(null);
  const [detail, setDetail] = useState<SharedTemplate | null>(null);
  const [previewMode, setPreviewMode] = useState<PreviewMode>('parameter');
  const [diffRequest, setDiffRequest] = useState<TemplateChangeRequestView | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingAction>(null);
  const accountsQuery = useQuery({ queryKey: ['channel-accounts'], queryFn: fetchChannelAccounts });
  const hasWhatsAppAccount = Boolean(accountsQuery.data?.some(isActiveWhatsAppAccount));
  const templatesQuery = useQuery({ queryKey: ['shared-templates', filters], queryFn: () => fetchSharedTemplates(filters), retry: false });
  const myRequestsQuery = useQuery({ queryKey: ['template-change-requests', 'mine'], queryFn: () => fetchMyTemplateChangeRequests(), retry: false });
  const reviewRequestsQuery = useQuery({ queryKey: ['template-change-requests', 'review'], queryFn: () => fetchTemplateChangeRequestsForReview(), enabled: isAdmin, retry: false });
  const detailQuery = useQuery({ queryKey: ['shared-template', detail?.id], queryFn: () => fetchSharedTemplate(detail!.id), enabled: Boolean(detail), retry: false });
  const operationsQuery = useQuery({ queryKey: ['shared-template-operations', detail?.id], queryFn: () => fetchTemplateOperations(detail!.id), enabled: Boolean(detail), retry: false });
  const refreshTemplates = () => queryClient.invalidateQueries({ queryKey: ['shared-templates'] });
  const refreshRequests = () => { void queryClient.invalidateQueries({ queryKey: ['template-change-requests'] }); };
  const requestChange = useMutation({
    mutationFn: ({ template, changeType, command, allowSend, remark }: { template: SharedTemplate; changeType: 'MODIFY' | 'SET_SEND_PERMISSION' | 'DELETE'; command?: Parameters<typeof createSharedTemplate>[0]; allowSend?: boolean; remark?: string }) => createTemplateChangeRequest(template.id, { changeType, expectedVersion: template.version, clientRequestId: requestId(), template: command ? (({ clientRequestId: _clientRequestId, ...draft }) => draft)(command) : undefined, allowSend, remark }),
    onSuccess: (outcome) => { setEditorOpen(false); setPendingAction(null); void refreshTemplates(); refreshRequests(); if (outcome.request) message.success(isAdmin ? '变更已直接执行' : '已提交审批'); }, onError: (error) => message.error(errorMessage(error)),
  });
  const createMutation = useMutation({ mutationFn: createSharedTemplate, onSuccess: () => { setEditorOpen(false); setPublicDraft(null); void refreshTemplates(); message.success('已提交官方审核'); }, onError: (error) => message.error(errorMessage(error)) });
  const syncMutation = useMutation({ mutationFn: syncSharedTemplates, onSuccess: (result) => { void refreshTemplates(); message.success(`同步完成：${result.fetched} 个模板，${result.changed} 个发生变化`); }, onError: (error) => message.error(errorMessage(error)) });
  const approveMutation = useMutation({ mutationFn: (request: TemplateChangeRequestView) => approveTemplateChangeRequest(request.id, requestId()), onSuccess: () => { void refreshTemplates(); refreshRequests(); message.success('已批准，正在执行'); }, onError: (error) => message.error(errorMessage(error)) });
  const rejectMutation = useMutation({ mutationFn: ({ request, reason }: { request: TemplateChangeRequestView; reason: string }) => rejectTemplateChangeRequest(request.id, reason), onSuccess: () => { refreshRequests(); message.success('已拒绝申请'); }, onError: (error) => message.error(errorMessage(error)) });
  const retryMutation = useMutation({ mutationFn: (request: TemplateChangeRequestView) => retryTemplateChangeRequest(request.id, requestId()), onSuccess: () => { void refreshTemplates(); refreshRequests(); message.success('已重新执行'); }, onError: (error) => message.error(errorMessage(error)) });
  const currentDetail = detailQuery.data ?? detail;
  const rows = templatesQuery.data?.items ?? [];
  const noAccountNotice = accountsQuery.isSuccess && !hasWhatsAppAccount;
  function openEditor(template: SharedTemplate | null, draft?: PublicTemplateConversionResult) { setEditing(template); setPublicDraft(draft ?? null); setEditorOpen(true); }
  function submitEditor(command: Parameters<typeof createSharedTemplate>[0], editingExisting: boolean, remark?: string) { if (!hasWhatsAppAccount) return Promise.resolve(); return editingExisting ? requestChange.mutateAsync({ template: editing!, changeType: 'MODIFY', command, remark }).then(() => undefined) : createMutation.mutateAsync(command).then(() => undefined); }
  return <div style={{ width: '100%', minWidth: 0 }}><Space direction="vertical" size="large" style={{ width: '100%' }}>
    <Space align="center" wrap style={{ width: '100%', justifyContent: 'space-between' }}><div><Title level={2} style={{ margin: 0 }}>共享模板</Title><Typography.Text type="secondary">系统内所有用户共用同一 WhatsApp 模板目录</Typography.Text></div><Space><Button icon={<ReloadOutlined />} loading={syncMutation.isPending} disabled={!hasWhatsAppAccount} onClick={() => syncMutation.mutate()}>同步模板</Button><Button type="primary" disabled={!hasWhatsAppAccount} onClick={() => openEditor(null)}>新建模板</Button></Space></Space>
    {noAccountNotice && <Alert type="warning" showIcon message="没有可用的 WhatsApp 账号" description="配置并启用 WhatsApp 账号后可申请新模板、同步和上传素材。" />}
    <Tabs destroyOnHidden items={[
      { key: 'shared', label: '共享模板', children: <Space direction="vertical" size="middle" style={{ width: '100%' }}><Space wrap><Input aria-label="共享模板搜索" placeholder="搜索名称或代码" prefix={<SearchOutlined />} value={filters.search ?? ''} onChange={(event) => setFilters((current) => ({ ...current, search: event.target.value || undefined, page: 1 }))} style={{ width: 240 }} /><Select aria-label="审核状态" allowClear placeholder="审核状态" value={filters.status} onChange={(value) => setFilters((current) => ({ ...current, status: value, page: 1 }))} options={[{ value: 'APPROVED', label: '已通过' }, { value: 'PENDING', label: '审核中' }, { value: 'REJECTED', label: '已拒绝' }]} style={{ width: 130 }} /><Select aria-label="语言" allowClear placeholder="语言" value={filters.language} onChange={(value) => setFilters((current) => ({ ...current, language: value, page: 1 }))} options={WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS} style={{ width: 180 }} /></Space>{templatesQuery.error && <Alert type="error" showIcon message={errorMessage(templatesQuery.error)} />}<SharedTemplateCardGrid templates={rows} loading={templatesQuery.isLoading} emptyDescription="暂无共享模板" isAdmin={isAdmin} onOpen={(template) => { setDetail(template); setPreviewMode('parameter'); }} onEdit={(template) => openEditor(template)} onToggleSend={(template) => setPendingAction({ type: 'permission', template, allowSend: !template.allowSend })} onDelete={(template) => setPendingAction({ type: 'delete', template })} /><Pagination current={templatesQuery.data?.page ?? filters.page ?? 1} pageSize={templatesQuery.data?.size ?? filters.size ?? 20} total={templatesQuery.data?.total ?? 0} showSizeChanger={false} onChange={(page) => setFilters((current) => ({ ...current, page }))} /></Space> },
      { key: 'public', label: '公共模板库', children: <PublicTemplateLibrary hasWhatsAppAccount={hasWhatsAppAccount} onCreateFromPublicTemplate={(draft) => openEditor(null, draft)} /> },
      { key: 'mine', label: '我的申请', children: <TemplateChangeRequestList loading={myRequestsQuery.isLoading} requests={myRequestsQuery.data?.items ?? []} onOpen={setDiffRequest} /> },
      ...(isAdmin ? [{ key: 'review', label: '变更审批', children: <TemplateAdminApprovalPanel loading={reviewRequestsQuery.isLoading} requests={reviewRequestsQuery.data?.items ?? []} approvingId={approveMutation.variables?.id ?? null} retryingId={retryMutation.variables?.id ?? null} onOpen={setDiffRequest} onApprove={(request) => approveMutation.mutate(request)} onReject={(request, reason) => { if (!reason.trim()) { message.error('拒绝理由不能为空'); return; } rejectMutation.mutate({ request, reason }); }} onRetry={(request) => retryMutation.mutate(request)} /> }] : []),
    ]} />
  </Space><TemplateEditorDrawer open={editorOpen} template={editing} initialValue={publicDraft?.initialValue} sourceContext={publicDraft} submitting={createMutation.isPending || requestChange.isPending} uploadMedia={(format, file, clientRequestId, signal): Promise<TemplateMediaAsset> => recoverTemplateMediaUpload({ clientRequestId, signal, upload: (stableId, requestSignal) => uploadTemplateMedia(format, file, stableId, requestSignal), find: (stableId, requestSignal) => fetchTemplateMediaUpload(stableId, requestSignal) })} onClose={() => { setEditorOpen(false); setEditing(null); setPublicDraft(null); }} onSubmit={submitEditor} /><SharedTemplateDetailModal template={currentDetail} operations={operationsQuery.data ?? []} open={Boolean(detail)} previewMode={previewMode} onPreviewModeChange={setPreviewMode} onEdit={(template) => { setDetail(null); openEditor(template); }} onToggleSend={(template) => setPendingAction({ type: 'permission', template, allowSend: !template.allowSend })} onDelete={(template) => setPendingAction({ type: 'delete', template })} onClose={() => setDetail(null)} /><TemplateChangeDiffModal request={diffRequest} open={Boolean(diffRequest)} onClose={() => setDiffRequest(null)} /><Modal open={Boolean(pendingAction)} title={pendingAction?.type === 'delete' ? '删除共享模板' : pendingAction?.allowSend ? '恢复模板发送' : '暂停模板发送'} onCancel={() => setPendingAction(null)} footer={<Space><Button onClick={() => setPendingAction(null)}>取消</Button><Button danger={pendingAction?.type === 'delete'} type="primary" loading={requestChange.isPending} onClick={() => { if (!pendingAction) return; requestChange.mutate({ template: pendingAction.template, changeType: pendingAction.type === 'delete' ? 'DELETE' : 'SET_SEND_PERMISSION', allowSend: pendingAction.allowSend }); }}>{isAdmin ? '确认影响所有用户' : '提交审批'}</Button></Space>}><Typography.Paragraph>{isAdmin ? '此变更会影响所有用户。请确认后继续。' : '该共享模板变更需要管理员审批，提交后不会立即改变模板。'}</Typography.Paragraph></Modal></div>;
}
