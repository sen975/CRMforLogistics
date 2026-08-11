import { useCallback, useMemo, useState } from 'react';
import { Alert, App, Button, Input, Modal, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, PauseCircleOutlined, PlayCircleOutlined, ReloadOutlined, SearchOutlined, EyeOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createAdminTemplate, deleteAdminTemplate, fetchAdminTemplate, fetchAdminTemplates, fetchChannelAccounts, fetchTemplateOperations, setAdminTemplateSendPermission, syncAdminTemplates, updateAdminTemplate, uploadTemplateMedia } from '../api/endpoints';
import type { TemplateAdmin, TemplateListFilters, TemplateMediaAsset, TemplateOperation } from '../api/types';
import TemplateDetailDrawer from '../components/templates/TemplateDetailDrawer';
import TemplateEditorDrawer from '../components/templates/TemplateEditorDrawer';
import { requestId, statusColors, statusLabels } from '../components/templates/templateUi';

const { Title, Text } = Typography;

function errorDetails(error: unknown): { status?: number; message: string; traceId?: string } {
  const response = (error as { response?: { status?: number; data?: { message?: string; traceId?: string } } }).response;
  return { status: response?.status, message: response?.data?.message ?? '操作失败，请稍后重试', traceId: response?.data?.traceId };
}

export default function TemplatesPage() {
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const [filters, setFilters] = useState<TemplateListFilters>({ page: 1, size: 20 });
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<TemplateAdmin | null>(null);
  const [detail, setDetail] = useState<TemplateAdmin | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const [operations, setOperations] = useState<TemplateOperation[]>([]);
  const [confirm, setConfirm] = useState<{ type: 'pause' | 'resume' | 'delete'; template: TemplateAdmin } | null>(null);
  const [pageError, setPageError] = useState<{ message: string; traceId?: string } | null>(null);
  const [operationNotice, setOperationNotice] = useState<string | null>(null);
  const [syncNotice, setSyncNotice] = useState<string | null>(null);

  const accountsQuery = useQuery({ queryKey: ['channel-accounts'], queryFn: fetchChannelAccounts });
  const account = accountsQuery.data?.[0];
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

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['admin-templates', account?.id] });
  const createMutation = useMutation({ mutationFn: (command: Parameters<typeof createAdminTemplate>[1]) => createAdminTemplate(account!.id, command), onSuccess: (result) => { setEditorOpen(false); refresh(); if (result.operationStatus === 'SUBMISSION_UNKNOWN') setOperationNotice('提交结果未知，请通过操作记录和同步结果确认最终状态'); else message.success('模板已提交审核'); }, onError: (error) => setOperationNotice(errorDetails(error).message) });
  const updateMutation = useMutation({ mutationFn: ({ code, language, command }: { code: string; language: string; command: Parameters<typeof updateAdminTemplate>[3] }) => updateAdminTemplate(account!.id, code, language, command), onSuccess: (result) => { setEditorOpen(false); refresh(); if (result.operationStatus === 'SUBMISSION_UNKNOWN') setOperationNotice('提交结果未知，请通过操作记录和同步结果确认最终状态'); else setOperationNotice('已提交修改，模板将重新进入审核'); }, onError: (error) => setOperationNotice(errorDetails(error).message) });
  const permissionMutation = useMutation({ mutationFn: ({ template, allowSend }: { template: TemplateAdmin; allowSend: boolean }) => setAdminTemplateSendPermission(account!.id, template.templateCode, template.language, allowSend, requestId()), onSuccess: () => { setConfirm(null); refresh(); message.success('发送状态已更新'); }, onError: (error) => setOperationNotice(errorDetails(error).message) });
  const deleteMutation = useMutation({ mutationFn: (template: TemplateAdmin) => deleteAdminTemplate(account!.id, template.templateCode, template.language, requestId()), onSuccess: () => { setConfirm(null); refresh(); message.success('模板已删除'); }, onError: (error) => setOperationNotice(errorDetails(error).message) });
  const syncMutation = useMutation({ mutationFn: () => syncAdminTemplates(account!.id), onSuccess: (result) => { refresh(); setSyncNotice(result.complete ? `同步完成：${result.fetched} 个模板，${result.changed} 个发生变化` : '同步部分完成，请查看结果并重试'); }, onError: (error) => setSyncNotice(errorDetails(error).message) });

  const rows = templatesQuery.data?.items ?? [];
  const openDetail = useCallback((row: TemplateAdmin) => {
    setDetail(row);
    setDetailOpen(true);
    if (account) void fetchTemplateOperations(account.id, row.templateCode, row.language).then(setOperations).catch(() => setOperations([]));
  }, [account]);

  const tableColumns = useMemo(() => [
    { title: '模板名称', dataIndex: 'name', key: 'name', render: (value: string, row: TemplateAdmin) => <Space direction="vertical" size={0}><Text strong>{value}</Text><Text type="secondary">{row.templateCode}</Text></Space> },
    { title: '语言', dataIndex: 'language', key: 'language', width: 100 },
    { title: '类别', dataIndex: 'category', key: 'category', width: 100, render: (value: string | null) => value === 'MARKETING' ? '营销' : value === 'UTILITY' ? '工具' : '未提供' },
    { title: '审核状态', dataIndex: 'reviewStatus', key: 'reviewStatus', width: 120, render: (value: TemplateAdmin['reviewStatus']) => <Tag color={statusColors[value]}>{statusLabels[value]}</Tag> },
    { title: '拒绝原因', dataIndex: 'rejectionReason', key: 'rejectionReason', ellipsis: true, render: (value: string | null) => value ?? '-' },
    { title: '发送状态', dataIndex: 'allowSend', key: 'allowSend', width: 100, render: (value: boolean) => <Tag color={value ? 'green' : 'default'}>{value ? '已启用' : '已暂停'}</Tag> },
    { title: '操作', key: 'actions', width: 152, fixed: 'right' as const, render: (_: unknown, row: TemplateAdmin) => <Space size={2}>{[
      <Tooltip key="view" title="查看详情"><Button type="text" aria-label={`查看 ${row.templateCode}`} icon={<EyeOutlined />} onClick={() => openDetail(row)} /> </Tooltip>,
      <Tooltip key="edit" title="编辑"><Button type="text" aria-label={`编辑 ${row.templateCode}`} icon={<EditOutlined />} onClick={() => { setEditing(row); setEditorOpen(true); }} /></Tooltip>,
      <Tooltip key="permission" title={row.allowSend ? '暂停发送' : '恢复发送'}><Button type="text" aria-label={`${row.allowSend ? '暂停发送' : '恢复发送'} ${row.templateCode}`} icon={row.allowSend ? <PauseCircleOutlined /> : <PlayCircleOutlined />} onClick={() => setConfirm({ type: row.allowSend ? 'pause' : 'resume', template: row })} /></Tooltip>,
      <Tooltip key="delete" title="删除"><Button type="text" danger aria-label={`删除 ${row.templateCode}`} icon={<DeleteOutlined />} onClick={() => setConfirm({ type: 'delete', template: row })} /></Tooltip>,
    ]}</Space> },
  ], [openDetail]);

  const queryError = templatesQuery.error ? errorDetails(templatesQuery.error) : null;
  if (queryError?.status === 403 && !pageError) setPageError({ message: '你没有模板管理权限', traceId: queryError.traceId });

  return <div style={{ width: '100%', minWidth: 0 }}>
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space align="center" style={{ justifyContent: 'space-between', width: '100%', flexWrap: 'wrap' }}><Title level={4} style={{ margin: 0 }}>WhatsApp 模板管理</Title><Space wrap><Button type="primary" onClick={() => { setEditing(null); setEditorOpen(true); }}>新建模板</Button><Tooltip title="同步模板"><Button aria-label="同步模板" icon={<ReloadOutlined />} loading={syncMutation.isPending} onClick={() => syncMutation.mutate()} /></Tooltip></Space></Space>
      {pageError && <Alert type="error" showIcon message={pageError.message} description={pageError.traceId ? `追踪 ID：${pageError.traceId}` : undefined} />}
      {operationNotice && <Alert type="warning" showIcon closable message={operationNotice} onClose={() => setOperationNotice(null)} />}
      {syncNotice && <Alert type="info" showIcon closable message={syncNotice} onClose={() => setSyncNotice(null)} />}
      <Space wrap style={{ width: '100%' }}>
        <Input aria-label="模板搜索" placeholder="搜索名称或代码" prefix={<SearchOutlined />} value={filters.search ?? ''} allowClear onChange={(event) => setFilters((current) => ({ ...current, page: 1, search: event.target.value || undefined }))} style={{ width: 240 }} />
        <Select aria-label="审核状态" placeholder="审核状态" allowClear value={filters.status} onChange={(value) => setFilters((current) => ({ ...current, page: 1, status: value }))} options={[{ value: 'PENDING', label: '审核中' }, { value: 'APPROVED', label: '已通过' }, { value: 'REJECTED', label: '已拒绝' }, { value: 'SUSPENDED', label: '已暂停' }, { value: 'UNKNOWN', label: '未知' }]} style={{ width: 140 }} />
        <Select aria-label="模板类别" placeholder="模板类别" allowClear value={filters.category} onChange={(value) => setFilters((current) => ({ ...current, page: 1, category: value }))} options={[{ value: 'UTILITY', label: '工具' }, { value: 'MARKETING', label: '营销' }]} style={{ width: 130 }} />
        <Select aria-label="语言" placeholder="语言" allowClear value={filters.language} onChange={(value) => setFilters((current) => ({ ...current, page: 1, language: value }))} options={[{ value: 'zh_CN', label: '简体中文' }, { value: 'en_US', label: '英语' }]} style={{ width: 130 }} />
        <Select aria-label="发送状态" placeholder="发送状态" allowClear value={filters.allowSend} onChange={(value) => setFilters((current) => ({ ...current, page: 1, allowSend: value }))} options={[{ value: true, label: '已启用' }, { value: false, label: '已暂停' }]} style={{ width: 130 }} />
        <Select aria-label="删除状态" placeholder="删除状态" allowClear value={filters.deleted} onChange={(value) => setFilters((current) => ({ ...current, page: 1, deleted: value }))} options={[{ value: true, label: '已删除' }, { value: false, label: '未删除' }]} style={{ width: 130 }} />
      </Space>
      <Table rowKey={(row) => `${row.templateCode}:${row.language}`} loading={templatesQuery.isLoading} dataSource={rows} columns={tableColumns} scroll={{ x: 920 }} pagination={{ current: templatesQuery.data?.page ?? 1, pageSize: templatesQuery.data?.size ?? 20, total: templatesQuery.data?.total ?? 0, showSizeChanger: false, onChange: (page) => setFilters((current) => ({ ...current, page })) }} locale={{ emptyText: pageError ? '暂无可显示的模板' : '暂无模板' }} />
    </Space>
    <TemplateEditorDrawer open={editorOpen} template={editing} submitting={createMutation.isPending || updateMutation.isPending} uploadMedia={(format, file) => uploadTemplateMedia(account!.id, format, file)} onClose={() => setEditorOpen(false)} onSubmit={async (command, isEditing) => { if (isEditing && editing) await updateMutation.mutateAsync({ code: editing.templateCode, language: editing.language, command: { ...command, clientRequestId: command.clientRequestId } }); else await createMutation.mutateAsync(command); }} />
    <TemplateDetailDrawer open={detailOpen} template={detailQuery.data ?? detail} operations={operations} loading={detailQuery.isLoading} onClose={() => setDetailOpen(false)} />
    <Modal open={!!confirm} title={confirm?.type === 'delete' ? '删除模板' : confirm?.type === 'pause' ? '暂停发送' : '恢复发送'} onCancel={() => setConfirm(null)} footer={<Space><Button onClick={() => setConfirm(null)}>取消</Button><Button danger={confirm?.type === 'delete'} type="primary" loading={permissionMutation.isPending || deleteMutation.isPending} onClick={() => { if (!confirm) return; if (confirm.type === 'delete') deleteMutation.mutate(confirm.template); else permissionMutation.mutate({ template: confirm.template, allowSend: confirm.type === 'resume' }); }}>{confirm?.type === 'delete' ? '确认删除' : '确认'}</Button></Space>}>
      <Typography.Paragraph>{confirm?.type === 'delete' ? '删除后模板将不再用于发送，确认删除？' : confirm?.type === 'pause' ? '确认暂停该模板发送？' : '确认恢复该模板发送？'}</Typography.Paragraph>
    </Modal>
  </div>;
}
