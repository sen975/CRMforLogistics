import { useMemo, useState } from 'react';
import { Alert, App, Button, Form, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { HistoryOutlined, ReloadOutlined, SwapOutlined, UndoOutlined, UserAddOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  assignAdminWhatsAppAccount,
  fetchAdminUsers,
  fetchAdminWhatsAppAccounts,
  fetchAdminWhatsAppAssignmentHistory,
  fetchWhatsAppAccounts,
  reclaimAdminWhatsAppAccount,
  syncAdminWhatsAppAccounts,
  transferAdminWhatsAppAccount,
} from '../../api/endpoints';
import type { AdminWhatsAppAccountProjection, AdminWhatsAppAssignmentAuditProjection } from '../../api/types';

const { Text } = Typography;

type AssignmentAction = 'assign' | 'transfer' | 'reclaim';
type AssignmentModal = { action: AssignmentAction; account: AdminWhatsAppAccountProjection };

function providerStatusTag(status: string | null) {
  if (status === 'ACTIVE') return <Tag color="green">可发送</Tag>;
  return <Tag color="orange">{status || '不可用'}</Tag>;
}

function verificationStatusTag(status: string | null) {
  if (status === 'VERIFIED') return <Tag color="green">已验证</Tag>;
  return <Tag color="orange">{status || '未验证'}</Tag>;
}

function errorStatus(error: unknown): number | undefined {
  if (!error || typeof error !== 'object' || !('response' in error)) return undefined;
  const response = (error as { response?: { status?: unknown } }).response;
  return typeof response?.status === 'number' ? response.status : undefined;
}

export function AdminWhatsAppAccountPanel({ isAdmin }: { isAdmin: boolean }) {
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const [assignment, setAssignment] = useState<AssignmentModal | null>(null);
  const [historyAccount, setHistoryAccount] = useState<AdminWhatsAppAccountProjection | null>(null);
  const [syncSummary, setSyncSummary] = useState<string | null>(null);
  const [assignmentForm] = Form.useForm<{ targetOwnerId?: string; reason: string }>();
  const adminAccounts = useQuery({
    queryKey: ['adminWhatsAppAccounts'],
    queryFn: fetchAdminWhatsAppAccounts,
    enabled: isAdmin,
    retry: false,
  });
  const salesAccounts = useQuery({
    queryKey: ['whatsappAccounts'],
    queryFn: fetchWhatsAppAccounts,
    enabled: !isAdmin,
    retry: false,
  });
  const users = useQuery({
    queryKey: ['adminUsers', 'whatsappAssignment'],
    queryFn: () => fetchAdminUsers(0, 100),
    enabled: isAdmin,
    retry: false,
  });
  const history = useQuery({
    queryKey: ['adminWhatsAppAssignmentHistory', historyAccount?.accountId],
    queryFn: () => fetchAdminWhatsAppAssignmentHistory(historyAccount!.accountId),
    enabled: !!historyAccount,
    retry: false,
  });
  const refreshAccounts = () => void queryClient.invalidateQueries({ queryKey: ['adminWhatsAppAccounts'] });
  const handleMutationError = (error: unknown) => {
    if (errorStatus(error) === 409) {
      message.error('账号状态已变化，已刷新当前列表');
      refreshAccounts();
      return;
    }
    message.error('操作失败，请稍后重试');
  };
  const sync = useMutation({
    mutationFn: syncAdminWhatsAppAccounts,
    onSuccess: (result) => {
      setSyncSummary(`本次同步：导入 ${result.importedCount}，刷新 ${result.refreshedCount}，不可用 ${result.unavailableCount}`);
      message.success('CAMS 同步完成');
      refreshAccounts();
    },
    onError: handleMutationError,
  });
  const assignmentMutation = useMutation({
    mutationFn: async (values: { targetOwnerId?: string; reason: string }) => {
      if (!assignment) throw new Error('ASSIGNMENT_ACTION_MISSING');
      const request = { reason: values.reason.trim(), expectedVersion: assignment.account.version };
      if (assignment.action === 'reclaim') {
        return reclaimAdminWhatsAppAccount(assignment.account.accountId, request);
      }
      const targetOwnerId = values.targetOwnerId;
      if (!targetOwnerId) throw new Error('TARGET_OWNER_REQUIRED');
      const command = { ...request, targetOwnerId };
      return assignment.action === 'assign'
        ? assignAdminWhatsAppAccount(assignment.account.accountId, command)
        : transferAdminWhatsAppAccount(assignment.account.accountId, command);
    },
    onSuccess: () => {
      message.success('账号分配已更新');
      assignmentForm.resetFields();
      setAssignment(null);
      refreshAccounts();
    },
    onError: handleMutationError,
  });
  const ownerNames = useMemo(
    () => new Map((users.data?.items ?? []).map((user) => [user.id, user.displayName || user.username])),
    [users.data?.items],
  );
  const selectableSales = (users.data?.items ?? []).filter(
    (user) => user.status === 'active' && !user.roles.includes('admin'),
  );

  if (!isAdmin) {
    return <section aria-label="我的 WhatsApp 发送账号">
      <Typography.Title level={5}>我的 WhatsApp 发送账号</Typography.Title>
      {salesAccounts.isLoading ? <Text type="secondary">正在加载账号…</Text> : null}
      {!salesAccounts.isLoading && !salesAccounts.data?.length
        ? <Alert type="info" showIcon message="当前没有已分配的 WhatsApp 发送账号" />
        : <Space direction="vertical" style={{ width: '100%' }}>
          {salesAccounts.data?.map((account) => <Space key={account.accountId} wrap>
            <Text strong>{account.name}</Text>
            <Text type="secondary">{account.maskedPhone}</Text>
            {providerStatusTag(account.providerStatus)}
            {verificationStatusTag(account.verificationStatus)}
          </Space>)}
        </Space>}
    </section>;
  }

  const columns = [
    {
      title: '号码',
      dataIndex: 'maskedPhone',
      key: 'maskedPhone',
      render: (maskedPhone: string) => <Text>{maskedPhone}</Text>,
    },
    { title: '账号名称', dataIndex: 'name', key: 'name' },
    {
      title: '服务商状态',
      dataIndex: 'providerStatus',
      key: 'providerStatus',
      render: providerStatusTag,
    },
    {
      title: '验证状态',
      dataIndex: 'verificationStatus',
      key: 'verificationStatus',
      render: verificationStatusTag,
    },
    {
      title: '当前销售',
      dataIndex: 'ownerUserId',
      key: 'ownerUserId',
      render: (ownerUserId: string | null) => ownerUserId
        ? ownerNames.get(ownerUserId) || '已分配销售'
        : <Text type="secondary">未分配</Text>,
    },
    {
      title: '操作',
      key: 'actions',
      render: (_value: unknown, account: AdminWhatsAppAccountProjection) => <Space size="small">
        {account.ownerUserId
          ? <>
            <Button size="small" icon={<SwapOutlined />} onClick={() => setAssignment({ action: 'transfer', account })}>转交</Button>
            <Button size="small" danger icon={<UndoOutlined />} onClick={() => setAssignment({ action: 'reclaim', account })}>收回</Button>
          </>
          : <Button size="small" type="primary" icon={<UserAddOutlined />} onClick={() => setAssignment({ action: 'assign', account })}>分配</Button>}
        <Button size="small" icon={<HistoryOutlined />} onClick={() => setHistoryAccount(account)}>历史</Button>
      </Space>,
    },
  ];
  const actionLabel = assignment?.action === 'assign' ? '分配'
    : assignment?.action === 'transfer' ? '转交' : '收回';

  return <section aria-label="管理员 WhatsApp 账号管理" style={{ marginBottom: 24 }}>
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>WhatsApp 账号管理</Typography.Title>
        <Button icon={<ReloadOutlined />} loading={sync.isPending} onClick={() => sync.mutate()}>同步 CAMS</Button>
      </Space>
      {syncSummary ? <Alert type="success" showIcon message={syncSummary} /> : null}
      {adminAccounts.isError ? <Alert type="error" showIcon message="WhatsApp 账号列表加载失败" /> : null}
      <Table
        rowKey="accountId"
        size="small"
        loading={adminAccounts.isLoading}
        dataSource={adminAccounts.data ?? []}
        columns={columns}
        pagination={false}
        scroll={{ x: 780 }}
        locale={{ emptyText: '暂无已同步的 WhatsApp 账号' }}
      />
    </Space>

    <Modal
      title={`${actionLabel} WhatsApp 账号`}
      open={!!assignment}
      onCancel={() => {
        assignmentForm.resetFields();
        setAssignment(null);
      }}
      onOk={() => assignmentForm.submit()}
      confirmLoading={assignmentMutation.isPending}
      okText={actionLabel}
      destroyOnHidden
    >
      <Form
        form={assignmentForm}
        layout="vertical"
        onFinish={(values) => assignmentMutation.mutate(values)}
      >
        {assignment?.action !== 'reclaim' ? <Form.Item
          name="targetOwnerId"
          label="目标销售"
          rules={[{ required: true, message: '请选择目标销售' }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            placeholder="选择可用销售"
            options={selectableSales.map((user) => ({
              value: user.id,
              label: `${user.displayName || user.username}（${user.username}）`,
            }))}
          />
        </Form.Item> : null}
        <Form.Item name="reason" label="原因" rules={[{ required: true, whitespace: true, message: '请填写原因' }]}>
          <Input.TextArea maxLength={500} showCount autoSize={{ minRows: 3, maxRows: 6 }} />
        </Form.Item>
      </Form>
    </Modal>

    <Modal
      title={historyAccount ? `${historyAccount.maskedPhone} 的分配历史` : '分配历史'}
      open={!!historyAccount}
      footer={<Button onClick={() => setHistoryAccount(null)}>关闭</Button>}
      onCancel={() => setHistoryAccount(null)}
      width={760}
      destroyOnHidden
    >
      {history.isError ? <Alert type="error" showIcon message="分配历史加载失败" /> : null}
      <Table<AdminWhatsAppAssignmentAuditProjection>
        rowKey="auditId"
        size="small"
        loading={history.isLoading}
        dataSource={history.data ?? []}
        pagination={false}
        columns={[
          { title: '动作', dataIndex: 'action', key: 'action' },
          { title: '原因', dataIndex: 'reason', key: 'reason' },
          {
            title: '时间',
            dataIndex: 'createdAt',
            key: 'createdAt',
            render: (createdAt: string) => new Date(createdAt).toLocaleString('zh-CN'),
          },
        ]}
      />
    </Modal>
  </section>;
}
