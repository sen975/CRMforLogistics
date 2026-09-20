import { useEffect, useState } from 'react';
import { Alert, App, Button, Input, Modal, Pagination, Select, Space, Tabs, Typography } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createPrivateTemplate, createSharedTemplate, createTemplateChangeRequest, deletePrivateTemplate, fetchAdminCams, fetchChannelAccounts, fetchMyTemplateChangeRequests, fetchPrivateTemplate, fetchPrivateTemplateOperations, fetchPrivateTemplates, fetchSharedTemplate, fetchSharedTemplates, fetchTemplateMediaUpload, fetchTemplateOperations, setPrivateTemplateSendPermission, syncPrivateTemplates, syncSharedTemplates, updatePrivateTemplate, uploadTemplateMedia } from '../api/endpoints';
import type { SharedTemplate, SharedTemplateListFilters, TemplateChangeRequestView, TemplateMediaAsset } from '../api/types';
import { useAuth } from '../hooks/useAuth';
import PublicTemplateLibrary from '../components/templates/PublicTemplateLibrary';
import SharedTemplateCardGrid from '../components/templates/SharedTemplateCardGrid';
import SharedTemplateDetailModal from '../components/templates/SharedTemplateDetailModal';
import TemplateChangeRequestList from '../components/templates/TemplateChangeRequestList';
import TemplateChangeDiffModal from '../components/templates/TemplateChangeDiffModal';
import TemplateEditorDrawer from '../components/templates/TemplateEditorDrawer';
import type { PublicTemplateConversionResult } from '../components/templates/publicTemplateConversion';
import type { PreviewMode } from '../components/templates/publicTemplatePreview';
import { recoverTemplateMediaUpload } from '../components/templates/templateMediaUpload';
import { requestId } from '../components/templates/templateUi';
import { WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS } from '../components/templates/whatsappLanguages';

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
  const [pendingAction, setPendingAction] = useState<PendingAction>(null);
  const [diffRequest, setDiffRequest] = useState<TemplateChangeRequestView | null>(null);
  const [scopeId, setScopeId] = useState<string>();
  const accountsQuery = useQuery({ queryKey: ['channel-accounts'], queryFn: fetchChannelAccounts });
  const currentAccount = accountsQuery.data?.find(isActiveWhatsAppAccount);
  const isPrivateDomain = currentAccount?.onboardingMode === 'BUSINESS_APP_COEXISTENCE';
  // Private templates and their media belong to the Business App account, never to a named CAMS space.
  const sharedScopeId = isPrivateDomain ? undefined : scopeId;
  const hasWhatsAppAccount = Boolean(currentAccount);
  // Every CAMS space owns its own library, so an administrator browsing the page picks the space
  // first. Other users only ever see their own space and must not send a scopeId at all.
  const camsQuery = useQuery({ queryKey: ['admin-cams'], queryFn: fetchAdminCams, enabled: isAdmin, retry: false });
  useEffect(() => { if (isAdmin && !scopeId && camsQuery.data?.length) setScopeId(camsQuery.data.find((item) => item.status === 'READY')?.scopeId ?? camsQuery.data[0].scopeId); }, [isAdmin, camsQuery.data, scopeId]);
  // The space resolves one render after the CAMS list arrives; keeping the previous page on screen
  // stops the grid from blanking out while the switch is in flight.
  const templatesQuery = useQuery({ queryKey: [isPrivateDomain ? 'private-templates' : 'shared-templates', currentAccount?.id, scopeId, filters], queryFn: () => isPrivateDomain ? fetchPrivateTemplates(currentAccount!.id, filters) : fetchSharedTemplates({ ...filters, scopeId }), placeholderData: (prev) => prev, retry: false });
  const myRequestsQuery = useQuery({ queryKey: ['template-change-requests', 'mine'], queryFn: () => fetchMyTemplateChangeRequests(), retry: false });
  const detailQuery = useQuery({ queryKey: [isPrivateDomain ? 'private-template' : 'shared-template', currentAccount?.id, scopeId, detail?.id], queryFn: () => isPrivateDomain ? fetchPrivateTemplate(currentAccount!.id, detail!.id) : fetchSharedTemplate(detail!.id, scopeId), enabled: Boolean(detail) && Boolean(currentAccount), retry: false });
  const operationsQuery = useQuery({ queryKey: [isPrivateDomain ? 'private-template-operations' : 'shared-template-operations', currentAccount?.id, scopeId, detail?.id], queryFn: () => isPrivateDomain ? fetchPrivateTemplateOperations(currentAccount!.id, detail!.id) : fetchTemplateOperations(detail!.id, undefined, undefined, scopeId), enabled: Boolean(detail) && Boolean(currentAccount), retry: false });
  const refreshTemplates = () => queryClient.invalidateQueries({ queryKey: [isPrivateDomain ? 'private-templates' : 'shared-templates'] });
  const refreshRequests = () => { void queryClient.invalidateQueries({ queryKey: ['template-change-requests'] }); };
  const requestChange = useMutation({
    mutationFn: ({ template, changeType, command, allowSend, remark }: { template: SharedTemplate; changeType: 'MODIFY' | 'SET_SEND_PERMISSION' | 'DELETE'; command?: Parameters<typeof createSharedTemplate>[0]; allowSend?: boolean; remark?: string }) => createTemplateChangeRequest(template.id, { changeType, expectedVersion: template.version, clientRequestId: requestId(), template: command ? (({ clientRequestId: _clientRequestId, ...draft }) => draft)(command) : undefined, allowSend, remark }, scopeId),
    onSuccess: (outcome) => { setEditorOpen(false); setPendingAction(null); void refreshTemplates(); refreshRequests(); if (outcome.request) message.success(isAdmin ? '变更已直接执行' : '已提交审批'); }, onError: (error) => message.error(errorMessage(error)),
  });
  const createMutation = useMutation({ mutationFn: (command: Parameters<typeof createSharedTemplate>[0]) => isPrivateDomain ? createPrivateTemplate(currentAccount!.id, command) : createSharedTemplate(command, scopeId), onSuccess: () => { setEditorOpen(false); setPublicDraft(null); void refreshTemplates(); message.success(isPrivateDomain ? '已提交 Business App 模板' : '已提交官方审核'); }, onError: (error) => message.error(errorMessage(error)) });
  const syncMutation = useMutation({ mutationFn: () => isPrivateDomain ? syncPrivateTemplates(currentAccount!.id) : syncSharedTemplates(scopeId), onSuccess: (result) => { void refreshTemplates(); message.success(`同步完成：${result.fetched} 个模板，${result.changed} 个发生变化`); }, onError: (error) => message.error(errorMessage(error)) });
  const currentDetail = detailQuery.data ?? detail;
  const rows = templatesQuery.data?.items ?? [];
  const noAccountNotice = accountsQuery.isSuccess && !hasWhatsAppAccount;
  function openEditor(template: SharedTemplate | null, draft?: PublicTemplateConversionResult) { setEditing(template); setPublicDraft(draft ?? null); setEditorOpen(true); }
  function submitEditor(command: Parameters<typeof createSharedTemplate>[0], editingExisting: boolean, remark?: string) { if (!hasWhatsAppAccount) return Promise.resolve(); if (isPrivateDomain && editingExisting) { const { language: _language, ...update } = command; return updatePrivateTemplate(currentAccount!.id, editing!.id, update).then(() => { setEditorOpen(false); void refreshTemplates(); }); } return editingExisting ? requestChange.mutateAsync({ template: editing!, changeType: 'MODIFY', command, remark }).then(() => undefined) : createMutation.mutateAsync(command).then(() => undefined); }
  return <div style={{ width: '100%', minWidth: 0 }}><Space direction="vertical" size="large" style={{ width: '100%' }}>
    <Space align="center" wrap className="template-management-header" style={{ width: '100%', justifyContent: 'space-between' }}><Typography.Title level={2} className="template-management-title">WhatsApp模板管理</Typography.Title><Space><Button icon={<ReloadOutlined />} loading={syncMutation.isPending} disabled={!hasWhatsAppAccount} onClick={() => syncMutation.mutate()}>同步模板</Button><Button type="primary" disabled={!hasWhatsAppAccount} onClick={() => openEditor(null)}>新建模板</Button></Space></Space>
    {noAccountNotice && <Alert type="warning" showIcon message="没有可用的 WhatsApp 账号" description="配置并启用 WhatsApp 账号后可申请新模板、同步和上传素材。" />}
    <Tabs destroyOnHidden items={[
      { key: 'shared', label: isPrivateDomain ? '我的 Business App 模板' : '共享模板', children: <Space direction="vertical" size="middle" className="template-management-tab-content" style={{ width: '100%' }}><Space wrap className="template-management-filters">{(camsQuery.data?.length ?? 0) > 1 ? <Select aria-label="选择 CAMS" value={scopeId} loading={camsQuery.isLoading} onChange={(value) => { setScopeId(value); setFilters((current) => ({ ...current, page: 1 })); }} options={(camsQuery.data ?? []).map((item) => ({ value: item.scopeId, label: `${item.displayName}（${item.custSpaceId}）` }))} style={{ minWidth: 300 }} /> : null}<Input aria-label="共享模板搜索" placeholder="搜索名称或代码" prefix={<SearchOutlined />} value={filters.search ?? ''} onChange={(event) => setFilters((current) => ({ ...current, search: event.target.value || undefined, page: 1 }))} style={{ width: 240 }} /><Select aria-label="审核状态" allowClear placeholder="审核状态" value={filters.status} onChange={(value) => setFilters((current) => ({ ...current, status: value, page: 1 }))} options={[{ value: 'APPROVED', label: '已通过' }, { value: 'PENDING', label: '审核中' }, { value: 'REJECTED', label: '已拒绝' }]} style={{ width: 130 }} /><Select aria-label="语言" allowClear placeholder="语言" value={filters.language} onChange={(value) => setFilters((current) => ({ ...current, language: value, page: 1 }))} options={WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS} style={{ width: 180 }} /></Space>{templatesQuery.error && <Alert type="error" showIcon message={errorMessage(templatesQuery.error)} />}<SharedTemplateCardGrid templates={rows} loading={templatesQuery.isLoading} emptyDescription={isPrivateDomain ? '暂无私有模板' : '暂无共享模板'} isAdmin={isPrivateDomain ? false : isAdmin} onOpen={(template) => { setDetail(template); setPreviewMode('parameter'); }} onEdit={(template) => openEditor(template)} onToggleSend={(template) => setPendingAction({ type: 'permission', template, allowSend: !template.allowSend })} onDelete={(template) => setPendingAction({ type: 'delete', template })} /><Pagination className="template-management-pagination" current={templatesQuery.data?.page ?? filters.page ?? 1} pageSize={templatesQuery.data?.size ?? filters.size ?? 20} total={templatesQuery.data?.total ?? 0} showSizeChanger={false} onChange={(page) => setFilters((current) => ({ ...current, page }))} /></Space> },
      { key: 'public', label: '公共模板库', children: <PublicTemplateLibrary hasWhatsAppAccount={hasWhatsAppAccount} scopeId={sharedScopeId} onCreateFromPublicTemplate={(draft) => openEditor(null, draft)} /> },
      ...(!isPrivateDomain ? [{ key: 'mine', label: '我的申请', children: <TemplateChangeRequestList loading={myRequestsQuery.isLoading} requests={myRequestsQuery.data?.items ?? []} onOpen={setDiffRequest} /> }] : []),
    ]} />
  </Space><TemplateEditorDrawer open={editorOpen} template={editing} initialValue={publicDraft?.initialValue} sourceContext={publicDraft} submitting={createMutation.isPending || requestChange.isPending} uploadMedia={(format, file, clientRequestId, signal): Promise<TemplateMediaAsset> => recoverTemplateMediaUpload({ clientRequestId, signal, upload: (stableId, requestSignal) => uploadTemplateMedia(format, file, stableId, requestSignal, undefined, sharedScopeId), find: (stableId, requestSignal) => fetchTemplateMediaUpload(stableId, requestSignal, undefined, sharedScopeId) })} onClose={() => { setEditorOpen(false); setEditing(null); setPublicDraft(null); }} onSubmit={submitEditor} /><SharedTemplateDetailModal template={currentDetail} operations={operationsQuery.data ?? []} open={Boolean(detail)} previewMode={previewMode} onPreviewModeChange={setPreviewMode} onEdit={(template) => { setDetail(null); openEditor(template); }} onToggleSend={(template) => setPendingAction({ type: 'permission', template, allowSend: !template.allowSend })} onDelete={(template) => setPendingAction({ type: 'delete', template })} onClose={() => setDetail(null)} /><Modal open={Boolean(pendingAction)} title={pendingAction?.type === 'delete' ? (isPrivateDomain ? '删除私有模板' : '删除共享模板') : pendingAction?.allowSend ? '恢复模板发送' : '暂停模板发送'} onCancel={() => setPendingAction(null)} footer={<Space><Button onClick={() => setPendingAction(null)}>取消</Button><Button danger={pendingAction?.type === 'delete'} type="primary" loading={requestChange.isPending} onClick={() => { if (!pendingAction) return; if (isPrivateDomain) { const action = pendingAction.type === 'delete' ? deletePrivateTemplate(currentAccount!.id, pendingAction.template.id) : setPrivateTemplateSendPermission(currentAccount!.id, pendingAction.template.id, pendingAction.allowSend!); action.then(() => { setPendingAction(null); void refreshTemplates(); }).catch((error) => message.error(errorMessage(error))); } else { requestChange.mutate({ template: pendingAction.template, changeType: pendingAction.type === 'delete' ? 'DELETE' : 'SET_SEND_PERMISSION', allowSend: pendingAction.allowSend }); } }}>{isPrivateDomain ? '确认直接执行' : isAdmin ? '确认影响所有用户' : '提交审批'}</Button></Space>}><Typography.Paragraph>{isPrivateDomain ? '该模板属于当前 Business App 账号，将直接执行。' : isAdmin ? '此变更会影响所有用户。请确认后继续。' : '该共享模板变更需要管理员审批，提交后不会立即改变模板。'}</Typography.Paragraph></Modal></div>;
}
