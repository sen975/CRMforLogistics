import { useEffect, useMemo, useState } from 'react';
import { Alert, App, Button, Form, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { HistoryOutlined, ReloadOutlined, SwapOutlined, UndoOutlined, UserAddOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { assignAdminScopedWhatsAppAccount, fetchAdminCams, fetchAdminScopedAssignmentHistory, fetchAdminScopedWhatsAppAccounts, fetchAdminUsers, fetchWhatsAppAccounts, reclaimAdminScopedWhatsAppAccount, syncAdminCams, transferAdminScopedWhatsAppAccount } from '../api/endpoints';
import type { AdminCamsScope, AdminScopedWhatsAppAccount, AdminWhatsAppAssignmentAuditProjection, AdminWhatsAppSyncResult, WhatsAppAccountProjection } from '../api/types';
import { AdminWhatsAppCallbackPanel } from '../components/whatsapp/AdminWhatsAppCallbackPanel';
import { camsSyncSummary, hasUnimportedProviderPhones } from '../components/whatsapp/camsSyncSummary';

const { Title, Text } = Typography;
type Action = 'assign' | 'transfer' | 'reclaim';

// The server's ChannelAccountException carries the error code as its message, so a blanket
// "账号状态已变化" reported every assignment failure as a version conflict. Translate the codes the
// server actually sends, and fall back to echoing the raw code so an unmapped failure stays
// diagnosable from the page.
const ASSIGNMENT_ERRORS: Record<string, string> = {
  WHATSAPP_ACCOUNT_NOT_SENDABLE: '该号码当前不可发送（本地状态已停用），请先在 CAMS 同步恢复后再操作',
  WHATSAPP_TARGET_USER_INVALID: '目标用户不存在或已停用，请重新选择',
  WHATSAPP_TARGET_ACCOUNT_ALREADY_EXISTS: '目标用户已持有另一个 WhatsApp 号码，请先收回再分配',
  WHATSAPP_ACCOUNT_NOT_FOUND: '账号不存在，请刷新列表后重试',
  WHATSAPP_ACCOUNT_SCOPE_MISMATCH: '账号不属于当前 CAMS，请刷新列表后重试',
  WHATSAPP_ASSIGNMENT_INPUT_INVALID: '提交内容不合法，请检查后重试',
  WHATSAPP_ADMIN_REQUIRED: '需要管理员权限',
  WHATSAPP_AUDIT_WRITE_FAILED: '分配审计写入失败，请稍后重试',
};
const SYNC_ERRORS: Record<string, string> = {
  WHATSAPP_CAMS_SYNC_UNAVAILABLE: '当前 CAMS 不可用或未就绪，无法同步',
  WHATSAPP_ADMIN_REQUIRED: '需要管理员权限',
  WHATSAPP_CAMS_SYNC_FAILED: 'CAMS 同步失败，请稍后重试',
};
const ASSIGNMENT_CONFLICT = 'WHATSAPP_ASSIGNMENT_CONFLICT';

function backendErrorCode(error: unknown): string | undefined {
  const code = (error as { response?: { data?: { code?: unknown } } })?.response?.data?.code;
  return typeof code === 'string' && code ? code : undefined;
}

function failureText(error: unknown, labels: Record<string, string>, fallback: string): string {
  const code = backendErrorCode(error);
  if (!code) return fallback;
  return labels[code] ?? `${fallback}（${code}）`;
}

function statusTag(value: string | null, good: string, goodLabel: string) { return <Tag color={value === good ? 'green' : 'orange'}>{value === good ? goodLabel : value || '未知'}</Tag>; }

export default function AdminWhatsAppAccountsPage() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [scopeId, setScopeId] = useState<string>();
  const [selectedAccount, setSelectedAccount] = useState<AdminScopedWhatsAppAccount | null>(null);
  const [action, setAction] = useState<Action | null>(null);
  const [historyAccount, setHistoryAccount] = useState<AdminScopedWhatsAppAccount | null>(null);
  const [syncResult, setSyncResult] = useState<AdminWhatsAppSyncResult | null>(null);
  const [form] = Form.useForm<{ targetOwnerId?: string; reason: string }>();
  const actionLabel = action === 'assign' ? '分配' : action === 'transfer' ? '转交' : '收回';
  const cams = useQuery({ queryKey: ['admin-cams'], queryFn: fetchAdminCams, retry: false });
  const accounts = useQuery({ queryKey: ['admin-cams-accounts', scopeId], queryFn: () => fetchAdminScopedWhatsAppAccounts(scopeId!), enabled: Boolean(scopeId), retry: false });
  const users = useQuery({ queryKey: ['admin-users', 'whatsapp-assignment'], queryFn: () => fetchAdminUsers(0, 100), retry: false });
  const myAccounts = useQuery({ queryKey: ['whatsapp-accounts', 'me'], queryFn: fetchWhatsAppAccounts, retry: false });
  const history = useQuery({ queryKey: ['admin-cams-history', scopeId, historyAccount?.accountId], queryFn: () => fetchAdminScopedAssignmentHistory(scopeId!, historyAccount!.accountId), enabled: Boolean(scopeId && historyAccount), retry: false });
  const refreshAccounts = () => void queryClient.invalidateQueries({ queryKey: ['admin-cams-accounts', scopeId] });
  const sync = useMutation({ mutationFn: () => syncAdminCams(scopeId!), onSuccess: (result) => { setSyncResult(result); refreshAccounts(); void queryClient.invalidateQueries({ queryKey: ['admin-cams'] }); }, onError: (error) => message.error(failureText(error, SYNC_ERRORS, '同步失败')) });
  const assignment = useMutation({ mutationFn: async (values: { targetOwnerId?: string; reason: string }) => { if (!scopeId || !selectedAccount || !action) throw new Error('assignment missing'); const request = { reason: values.reason.trim(), expectedVersion: selectedAccount.version }; if (action === 'reclaim') return reclaimAdminScopedWhatsAppAccount(scopeId, selectedAccount.accountId, request); if (!values.targetOwnerId) throw new Error('target owner missing'); return action === 'assign' ? assignAdminScopedWhatsAppAccount(scopeId, selectedAccount.accountId, { ...request, targetOwnerId: values.targetOwnerId }) : transferAdminScopedWhatsAppAccount(scopeId, selectedAccount.accountId, { ...request, targetOwnerId: values.targetOwnerId }); }, onSuccess: () => { message.success('账号分配已更新'); setAction(null); setSelectedAccount(null); form.resetFields(); refreshAccounts(); }, onError: (error) => { if (backendErrorCode(error) === ASSIGNMENT_CONFLICT) { message.error('账号状态已变化，已刷新当前列表，请重试'); refreshAccounts(); return; } message.error(failureText(error, ASSIGNMENT_ERRORS, `${actionLabel}失败`)); } });
  const scope = cams.data?.find((item) => item.scopeId === scopeId);
  // Sendability follows the provider status alone; the code verification is shown but not required.
  const usable = (accounts.data ?? []).filter((item) => item.providerStatus === 'ACTIVE');
  useEffect(() => { if (!scopeId && cams.data?.length) setScopeId(cams.data.find((item) => item.status === 'READY')?.scopeId ?? cams.data[0].scopeId); }, [cams.data, scopeId]);
  useEffect(() => { if (usable.length === 1) setSelectedAccount(usable[0]); else if (selectedAccount && !usable.some((item) => item.accountId === selectedAccount.accountId)) setSelectedAccount(null); }, [accounts.data, usable.length]);
  const ownerNames = useMemo(() => new Map((users.data?.items ?? []).map((user) => [user.id, user.displayName || user.username])), [users.data?.items]);
  const assignableOwners = (users.data?.items ?? []).filter((user) => user.status === 'active');
  const openAction = (nextAction: Action, account: AdminScopedWhatsAppAccount) => { setSelectedAccount(account); setAction(nextAction); form.resetFields(); };
  const columns = [
    { title: '号码', dataIndex: 'maskedPhone', key: 'maskedPhone' },
    { title: '账号名称', dataIndex: 'name', key: 'name' },
    { title: '服务商状态', dataIndex: 'providerStatus', key: 'providerStatus', render: (value: string | null) => statusTag(value, 'ACTIVE', '可发送') },
    { title: '验证状态', dataIndex: 'verificationStatus', key: 'verificationStatus', render: (value: string | null) => statusTag(value, 'VERIFIED', '已验证') },
    { title: '当前销售', dataIndex: 'ownerUserId', key: 'ownerUserId', render: (value: string | null) => value ? ownerNames.get(value) || '已分配销售' : <Text type="secondary">未分配</Text> },
    { title: '操作', key: 'actions', render: (_: unknown, account: AdminScopedWhatsAppAccount) => <Space size="small"><Button size="small" icon={account.ownerUserId ? <SwapOutlined /> : <UserAddOutlined />} onClick={() => openAction(account.ownerUserId ? 'transfer' : 'assign', account)}>{account.ownerUserId ? '转交' : '分配'}</Button>{account.ownerUserId ? <Button size="small" danger icon={<UndoOutlined />} onClick={() => openAction('reclaim', account)}>收回</Button> : null}<Button size="small" icon={<HistoryOutlined />} onClick={() => setHistoryAccount(account)}>历史</Button></Space> },
  ];
  return <div style={{ maxWidth: 1180, margin: '0 auto', padding: 24 }}>
    <Space align="center" style={{ width: '100%', justifyContent: 'space-between', marginBottom: 20 }}><div><Title level={3} style={{ margin: 0 }}>WhatsApp 账号管理</Title><Text type="secondary">先选择 CAMS，再管理该空间下的号码</Text></div><Button icon={<ReloadOutlined />} loading={sync.isPending} disabled={!scopeId || scope?.status !== 'READY'} onClick={() => sync.mutate()}>同步当前 CAMS</Button></Space>
    <Space direction="vertical" size="small" style={{ width: '100%', marginBottom: 24 }}>
      <Title level={5} style={{ margin: 0 }}>我持有的号码</Title>
      {myAccounts.isError ? <Alert type="error" showIcon message="我持有的号码加载失败" /> : null}
      <Table<WhatsAppAccountProjection> rowKey="accountId" size="small" loading={myAccounts.isLoading} dataSource={myAccounts.data ?? []} pagination={false} locale={{ emptyText: '当前没有归属你的可发送号码（已停用或已收回的号码不在此列出）' }} columns={[
        { title: '号码', dataIndex: 'maskedPhone', key: 'maskedPhone' },
        { title: '账号名称', dataIndex: 'name', key: 'name' },
        { title: '服务商状态', dataIndex: 'providerStatus', key: 'providerStatus', render: (value: string | null) => statusTag(value, 'ACTIVE', '可发送') },
        { title: '验证状态', dataIndex: 'verificationStatus', key: 'verificationStatus', render: (value: string | null) => statusTag(value, 'VERIFIED', '已验证') },
      ]} />
    </Space>
    <Space direction="vertical" style={{ width: '100%' }} size="middle"><Select aria-label="选择 CAMS" value={scopeId} onChange={(value) => { setScopeId(value); setSelectedAccount(null); setSyncResult(null); }} loading={cams.isLoading} options={(cams.data ?? []).map((item) => ({ value: item.scopeId, label: `${item.displayName}（${item.custSpaceId}）` }))} style={{ minWidth: 360 }} />{scope && scope.status !== 'READY' ? <Alert type="warning" showIcon message="当前 CAMS 已停用" /> : null}{syncResult ? <Alert type={hasUnimportedProviderPhones(syncResult) ? 'warning' : 'success'} showIcon message={camsSyncSummary(syncResult)} /> : null}{usable.length === 1 ? <Alert type="info" showIcon message={`已自动选择唯一可用号码：${usable[0].maskedPhone}`} /> : null}{accounts.isError ? <Alert type="error" showIcon message="账号列表加载失败" /> : null}<Table<AdminScopedWhatsAppAccount> rowKey="accountId" loading={accounts.isLoading} dataSource={accounts.data ?? []} columns={columns} pagination={false} scroll={{ x: 900 }} locale={{ emptyText: '当前 CAMS 暂无同步号码' }} /></Space>
    {scopeId && scope?.status === 'READY' ? <AdminWhatsAppCallbackPanel scopeId={scopeId} isAdmin /> : null}
    <Modal title={`${actionLabel} WhatsApp 账号`} open={Boolean(action && selectedAccount)} onCancel={() => { setAction(null); setSelectedAccount(null); form.resetFields(); }} onOk={() => form.submit()} confirmLoading={assignment.isPending} okText={actionLabel} destroyOnHidden><Form form={form} layout="vertical" onFinish={(values) => assignment.mutate(values)}>{action !== 'reclaim' ? <Form.Item name="targetOwnerId" label="目标用户" rules={[{ required: true, message: '请选择目标用户' }]}><Select showSearch optionFilterProp="label" placeholder="选择活跃用户" options={assignableOwners.map((user) => ({ value: user.id, label: `${user.displayName || user.username}（${user.username}）` }))} /></Form.Item> : null}<Form.Item name="reason" label="原因" rules={[{ required: true, whitespace: true, message: '请填写原因' }]}><Input.TextArea maxLength={500} showCount autoSize={{ minRows: 3, maxRows: 6 }} /></Form.Item></Form></Modal>
    <Modal title={historyAccount ? `${historyAccount.maskedPhone} 的分配历史` : '分配历史'} open={Boolean(historyAccount)} footer={<Button onClick={() => setHistoryAccount(null)}>关闭</Button>} onCancel={() => setHistoryAccount(null)} width={760} destroyOnHidden><Table<AdminWhatsAppAssignmentAuditProjection> rowKey="auditId" loading={history.isLoading} dataSource={history.data ?? []} pagination={false} columns={[{ title: '动作', dataIndex: 'action', key: 'action' }, { title: '原因', dataIndex: 'reason', key: 'reason' }, { title: '时间', dataIndex: 'createdAt', key: 'createdAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') }]} /></Modal>
  </div>;
}
