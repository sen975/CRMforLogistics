import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, App, Button, Input, Modal, Pagination, Select, Space, Tabs, Tooltip, Typography } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createAdminTemplate, deleteAdminTemplate, fetchAdminTemplate, fetchAdminTemplates, fetchChannelAccounts, fetchTemplateMediaUpload, fetchTemplateOperations, setAdminTemplateSendPermission, syncAdminTemplates, updateAdminTemplate, updateAdminTemplateRemark, uploadTemplateMedia } from '../api/endpoints';
import type { TemplateAdmin, TemplateListFilters, TemplateMediaAsset, TemplateOperation } from '../api/types';
import TemplateEditorDrawer from '../components/templates/TemplateEditorDrawer';
import TemplateRemarkModal from '../components/templates/TemplateRemarkModal';
import OwnedTemplateCardGrid from '../components/templates/OwnedTemplateCardGrid';
import OwnedTemplateDetailModal from '../components/templates/OwnedTemplateDetailModal';
import PublicTemplateLibrary from '../components/templates/PublicTemplateLibrary';
import type { PublicTemplateConversionResult } from '../components/templates/publicTemplateConversion';
import type { PreviewMode } from '../components/templates/publicTemplatePreview';
import { requestId } from '../components/templates/templateUi';
import { recoverTemplateMediaUpload } from '../components/templates/templateMediaUpload';
import { WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS } from '../components/templates/whatsappLanguages';

const { Title } = Typography;

function errorDetails(error: unknown): { status?: number; message: string; traceId?: string } {
  const response = (error as { response?: { status?: number; data?: { message?: string; traceId?: string } } }).response;
  return { status: response?.status, message: response?.data?.message ?? '操作失败，请稍后重试', traceId: response?.data?.traceId };
}

function isActiveWhatsAppAccount(account: { channelType: string; authStatus: string }): boolean {
  const channelType = account.channelType.trim().toLowerCase();
  return (channelType === 'whatsapp' || channelType === 'chatapp')
    && account.authStatus.trim().toLowerCase() === 'active';
}

export default function TemplatesPage() {
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const [filters, setFilters] = useState<TemplateListFilters>({ page: 1, size: 20 });
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<TemplateAdmin | null>(null);
  const [publicEditorDraft, setPublicEditorDraft] = useState<{
    accountId: string;
    value: PublicTemplateConversionResult;
  } | null>(null);
  const [remarkTemplate, setRemarkTemplate] = useState<TemplateAdmin | null>(null);
  const [remarkError, setRemarkError] = useState<string | null>(null);
  const [detail, setDetail] = useState<TemplateAdmin | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const [ownedPreviewMode, setOwnedPreviewMode] = useState<PreviewMode>('parameter');
  const [operations, setOperations] = useState<TemplateOperation[]>([]);
  const [operationsLoading, setOperationsLoading] = useState(false);
  const [operationsError, setOperationsError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<{ type: 'pause' | 'resume' | 'delete'; template: TemplateAdmin } | null>(null);
  const [operationNotice, setOperationNotice] = useState<string | null>(null);
  const [syncNotice, setSyncNotice] = useState<{ type: 'success' | 'warning' | 'error'; message: string } | null>(null);
  const historyRequestId = useRef(0);
  const activeAccountIdRef = useRef<string | null>(null);
  const editorSessionRef = useRef(0);
  const previousAccountIdRef = useRef<string | null | undefined>(undefined);

  const accountsQuery = useQuery({ queryKey: ['channel-accounts'], queryFn: fetchChannelAccounts });
  const account = accountsQuery.data?.find(isActiveWhatsAppAccount);
  const accountId = account?.id ?? null;
  const noActiveAccount = accountsQuery.isSuccess && !account;
  useEffect(() => {
    activeAccountIdRef.current = accountId;
    if (previousAccountIdRef.current === accountId) return;
    previousAccountIdRef.current = accountId;
    editorSessionRef.current += 1;
    setEditorOpen(false);
    setEditing(null);
    setPublicEditorDraft(null);
  }, [accountId]);
  const templatesQuery = useQuery({
    queryKey: ['admin-templates', account?.id, filters],
    queryFn: () => fetchAdminTemplates(account!.id, filters),
    enabled: !!account,
    retry: false,
  });
  const detailQuery = useQuery({
    queryKey: ['admin-template', account?.id, detail?.templateCode, detail?.language],
    queryFn: () => fetchAdminTemplate(account!.id, detail!.templateCode, detail!.language),
    enabled: !!account && detailOpen && !!detail,
    retry: false,
  });

  const refresh = (targetAccountId = accountId) => queryClient.invalidateQueries({ queryKey: ['admin-templates', targetAccountId] });
  const createMutation = useMutation({
    mutationFn: ({ accountId: targetAccountId, command }: { accountId: string; sessionId: number; command: Parameters<typeof createAdminTemplate>[1] }) => createAdminTemplate(targetAccountId, command),
    onSuccess: (result, variables) => {
      if (activeAccountIdRef.current !== variables.accountId || editorSessionRef.current !== variables.sessionId) return;
      setEditorOpen(false);
      setPublicEditorDraft(null);
      void refresh(variables.accountId);
      if (result.operationStatus === 'SUBMISSION_UNKNOWN') setOperationNotice('提交结果未知，请通过操作记录和同步结果确认最终状态'); else message.success('模板已提交审核');
    },
    onError: (error, variables) => {
      if (activeAccountIdRef.current === variables.accountId && editorSessionRef.current === variables.sessionId) setOperationNotice(errorDetails(error).message);
    },
  });
  const updateMutation = useMutation({ mutationFn: ({ code, language, command }: { code: string; language: string; command: Parameters<typeof updateAdminTemplate>[3] }) => updateAdminTemplate(account!.id, code, language, command), onSuccess: (result) => { setEditorOpen(false); refresh(); if (result.operationStatus === 'SUBMISSION_UNKNOWN') setOperationNotice('提交结果未知，请通过操作记录和同步结果确认最终状态'); else setOperationNotice('已提交修改，模板将重新进入审核'); }, onError: (error) => setOperationNotice(errorDetails(error).message) });
  const remarkMutation = useMutation({
    mutationFn: ({ template, remark }: { template: TemplateAdmin; remark: string }) => updateAdminTemplateRemark(account!.id, template.templateCode, template.language, remark),
    onSuccess: (result, variables) => {
      void queryClient.invalidateQueries({ queryKey: ['admin-templates', account?.id] });
      queryClient.setQueryData(['admin-template', account?.id, result.templateCode, result.language], result);
      setDetail((current) => current?.templateCode === result.templateCode && current.language === result.language ? result : current);
      setRemarkError(null);
      setRemarkTemplate((current) => current?.templateCode === variables.template.templateCode && current.language === variables.template.language ? null : current);
      message.success('备注已保存');
    },
    onError: (error, variables) => setRemarkTemplate((current) => {
      if (current?.templateCode === variables.template.templateCode && current.language === variables.template.language) {
        setRemarkError(errorDetails(error).message);
      }
      return current;
    }),
  });
  const permissionMutation = useMutation({ mutationFn: ({ template, allowSend }: { template: TemplateAdmin; allowSend: boolean }) => setAdminTemplateSendPermission(account!.id, template.templateCode, template.language, allowSend, requestId()), onSuccess: () => { setConfirm(null); refresh(); message.success('发送状态已更新'); }, onError: (error) => setOperationNotice(errorDetails(error).message) });
  const deleteMutation = useMutation({ mutationFn: (template: TemplateAdmin) => deleteAdminTemplate(account!.id, template.templateCode, template.language, requestId()), onSuccess: () => { setConfirm(null); refresh(); message.success('模板已删除'); }, onError: (error) => setOperationNotice(errorDetails(error).message) });
  const syncMutation = useMutation({ mutationFn: () => syncAdminTemplates(account!.id), onSuccess: (result) => { refresh(); setSyncNotice(result.complete ? { type: 'success', message: `同步完成：${result.fetched} 个模板，${result.changed} 个发生变化` } : result.pages === 0 && result.fetched === 0 && result.changed === 0 ? { type: 'error', message: '同步失败：未获取到任何模板，请检查同步条件后重试' } : { type: 'warning', message: `同步部分完成：${result.fetched} 个模板，${result.changed} 个发生变化，请重试未完成部分` }); }, onError: (error) => setSyncNotice({ type: 'error', message: `同步失败：${errorDetails(error).message}` }) });

  const rows = templatesQuery.data?.items ?? [];
  const openDetail = useCallback((row: TemplateAdmin) => {
    const currentRequestId = historyRequestId.current + 1;
    historyRequestId.current = currentRequestId;
    setDetail(row);
    setDetailOpen(true);
    setOwnedPreviewMode('parameter');
    setOperations([]);
    setOperationsError(null);
    setOperationsLoading(Boolean(account));
    if (account) {
      void fetchTemplateOperations(account.id, row.templateCode, row.language)
        .then((result) => {
          if (historyRequestId.current === currentRequestId) setOperations(result);
        })
        .catch((error) => {
          if (historyRequestId.current === currentRequestId) setOperationsError(errorDetails(error).message);
        })
        .finally(() => {
          if (historyRequestId.current === currentRequestId) setOperationsLoading(false);
        });
    }
  }, [account]);

  const queryError = templatesQuery.error ? errorDetails(templatesQuery.error) : null;
  const pageError = queryError ? {
    message: queryError.status === 403 ? '你没有模板管理权限' : queryError.message,
    traceId: queryError.traceId,
  } : null;
  const listLoading = accountsQuery.isLoading || templatesQuery.isLoading;

  return <div style={{ width: '100%', minWidth: 0 }}>
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space align="center" style={{ justifyContent: 'space-between', width: '100%', flexWrap: 'wrap' }}><Title level={4} style={{ margin: 0 }}>WhatsApp 模板管理</Title><Space wrap><Button type="primary" disabled={!account} onClick={() => { setEditing(null); setPublicEditorDraft(null); setEditorOpen(true); }}>新建模板</Button><Tooltip title="同步模板"><Button aria-label="同步模板" disabled={!account} icon={<ReloadOutlined />} loading={syncMutation.isPending} onClick={() => syncMutation.mutate()} /></Tooltip></Space></Space>
      {noActiveAccount && <Alert type="warning" showIcon message="没有可用的 WhatsApp 账号" />}
      {pageError && <Alert type="error" showIcon message={pageError.message} description={pageError.traceId ? `追踪 ID：${pageError.traceId}` : undefined} action={<Button size="small" onClick={() => void templatesQuery.refetch()}>重试加载模板</Button>} />}
      {listLoading && <Alert type="info" showIcon message="正在加载模板" />}
      {operationNotice && <Alert type="warning" showIcon closable message={operationNotice} onClose={() => setOperationNotice(null)} />}
      {syncNotice && <Alert type={syncNotice.type} showIcon closable message={syncNotice.message} onClose={() => setSyncNotice(null)} />}
      <Tabs destroyOnHidden defaultActiveKey="mine" items={[
        { key: 'mine', label: '我的模板', children: <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Space wrap style={{ width: '100%' }}>
            <Input aria-label="模板搜索" placeholder="搜索名称或代码" prefix={<SearchOutlined />} value={filters.search ?? ''} allowClear onChange={(event) => setFilters((current) => ({ ...current, page: 1, search: event.target.value || undefined }))} style={{ width: 240 }} />
            <Select aria-label="审核状态" placeholder="审核状态" allowClear value={filters.status} onChange={(value) => setFilters((current) => ({ ...current, page: 1, status: value }))} options={[{ value: 'PENDING', label: '审核中' }, { value: 'APPROVED', label: '已通过' }, { value: 'REJECTED', label: '已拒绝' }, { value: 'SUSPENDED', label: '已暂停' }, { value: 'UNKNOWN', label: '未知' }]} style={{ width: 140 }} />
            <Select aria-label="模板类别" placeholder="模板类别" allowClear value={filters.category} onChange={(value) => setFilters((current) => ({ ...current, page: 1, category: value }))} options={[{ value: 'UTILITY', label: '工具' }, { value: 'MARKETING', label: '营销' }]} style={{ width: 130 }} />
            <Select aria-label="语言" placeholder="语言" allowClear value={filters.language} onChange={(value) => setFilters((current) => ({ ...current, page: 1, language: value }))} options={WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS} showSearch optionFilterProp="label" style={{ width: 190 }} />
            <Select aria-label="发送状态" placeholder="发送状态" allowClear value={filters.allowSend} onChange={(value) => setFilters((current) => ({ ...current, page: 1, allowSend: value }))} options={[{ value: true, label: '已启用' }, { value: false, label: '已暂停' }]} style={{ width: 130 }} />
            <Select aria-label="删除状态" placeholder="删除状态" allowClear value={filters.deleted} onChange={(value) => setFilters((current) => ({ ...current, page: 1, deleted: value }))} options={[{ value: true, label: '已删除' }, { value: false, label: '未删除' }]} style={{ width: 130 }} />
          </Space>
          <OwnedTemplateCardGrid
            templates={rows}
            loading={listLoading}
            emptyDescription={pageError ? '暂无可显示的模板' : '暂无模板'}
            remarkPending={remarkMutation.isPending}
            onOpen={openDetail}
            onEdit={(row) => { setPublicEditorDraft(null); setEditing(row); setEditorOpen(true); }}
            onRemark={(row) => { setRemarkError(null); setRemarkTemplate(row); }}
            onToggleSend={(row) => setConfirm({ type: row.desiredAllowSend ? 'pause' : 'resume', template: row })}
            onDelete={(row) => setConfirm({ type: 'delete', template: row })}
          />
          <Pagination
            current={templatesQuery.data?.page ?? 1}
            pageSize={templatesQuery.data?.size ?? 20}
            total={templatesQuery.data?.total ?? 0}
            showSizeChanger={false}
            onChange={(page) => setFilters((current) => ({ ...current, page }))}
          />
        </Space> },
        { key: 'public', label: '公共模板库', children: <PublicTemplateLibrary account={account ?? null} onCreateFromPublicTemplate={(draft) => { if (!account) return; setEditing(null); setPublicEditorDraft({ accountId: account.id, value: draft }); setEditorOpen(true); }} /> },
      ]} />
    </Space>
    <TemplateEditorDrawer open={editorOpen} template={editing} initialValue={publicEditorDraft?.accountId === accountId ? publicEditorDraft.value.initialValue : null} sourceContext={publicEditorDraft?.accountId === accountId ? publicEditorDraft.value : null} submitting={createMutation.isPending || updateMutation.isPending} uploadMedia={(format, file, clientRequestId, signal) => recoverTemplateMediaUpload({ clientRequestId, signal, upload: (stableId, requestSignal) => uploadTemplateMedia(account!.id, format, file, stableId, requestSignal), find: (stableId, requestSignal) => fetchTemplateMediaUpload(account!.id, stableId, requestSignal) })} onClose={() => { setEditorOpen(false); setPublicEditorDraft(null); }} onSubmit={async (command, isEditing) => {
      if (isEditing && editing) {
        const { name, category, components, examples, messageSendTtlSeconds, clientRequestId } = command;
        await updateMutation.mutateAsync({
          code: editing.templateCode,
          language: editing.language,
          command: { name, category, components, examples, messageSendTtlSeconds, clientRequestId },
        });
      } else {
        if (!accountId) return;
        await createMutation.mutateAsync({ accountId, sessionId: editorSessionRef.current, command });
      }
    }} />
    {remarkTemplate && <TemplateRemarkModal open officialName={remarkTemplate.name} remark={remarkTemplate.remark} saving={remarkMutation.isPending} error={remarkError} onCancel={() => { setRemarkError(null); setRemarkTemplate(null); }} onSave={(remark) => { setRemarkError(null); remarkMutation.mutate({ template: remarkTemplate, remark }); }} />}
    <OwnedTemplateDetailModal
      open={detailOpen}
      template={detailQuery.data ?? detail}
      operations={operations}
      loading={detailQuery.isLoading}
      operationsLoading={operationsLoading}
      operationsError={operationsError}
      previewMode={ownedPreviewMode}
      onPreviewModeChange={setOwnedPreviewMode}
      onEdit={(row) => { setDetailOpen(false); setPublicEditorDraft(null); setEditing(row); setEditorOpen(true); }}
      onRemark={(row) => { setRemarkError(null); setRemarkTemplate(row); }}
      onToggleSend={(row) => setConfirm({ type: row.desiredAllowSend ? 'pause' : 'resume', template: row })}
      onDelete={(row) => setConfirm({ type: 'delete', template: row })}
      onClose={() => setDetailOpen(false)}
    />
    <Modal open={!!confirm} title={confirm?.type === 'delete' ? '删除模板' : confirm?.type === 'pause' ? '暂停发送' : '恢复发送'} onCancel={() => setConfirm(null)} footer={<Space><Button onClick={() => setConfirm(null)}>取消</Button><Button danger={confirm?.type === 'delete'} type="primary" loading={permissionMutation.isPending || deleteMutation.isPending} onClick={() => { if (!confirm) return; if (confirm.type === 'delete') deleteMutation.mutate(confirm.template); else permissionMutation.mutate({ template: confirm.template, allowSend: confirm.type === 'resume' }); }}>{confirm?.type === 'delete' ? '确认删除' : '确认'}</Button></Space>}>
      <Typography.Paragraph>{confirm?.type === 'delete' ? '删除后模板将不再用于发送，确认删除？' : confirm?.type === 'pause' ? '确认暂停该模板发送？' : '确认恢复该模板发送？'}</Typography.Paragraph>
    </Modal>
  </div>;
}
