import { useEffect, useState } from 'react';
import { Alert, App, Button, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import { ApiOutlined, EditOutlined, PlusOutlined, ReloadOutlined, StopOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { blockAdminCams, createAdminCams, fetchAdminCams, fetchAdminUsers, syncAdminCams, testAdminCams, updateAdminCams } from '../api/endpoints';
import type { AdminCamsRequest, AdminCamsScope, AdminCamsScopeType } from '../api/types';
import { templateScopeTypeLabels } from '../components/templates/templateUi';
import { camsSyncSummary, hasUnimportedProviderPhones } from '../components/whatsapp/camsSyncSummary';

const { Title, Text } = Typography;

function errorText(error: unknown): string {
  return (error as { response?: { data?: { message?: string; code?: string } } }).response?.data?.message
    ?? '操作失败，请稍后重试';
}

const SECOND_CAMS_TITLE = '再接入一个企业级 CAMS 空间？';
const SECOND_CAMS_BODY = '当前已存在一个企业级 CAMS 空间。再接入第二个企业级 CAMS 会让账号绑定无法跨空间进行（绑定会返回 WHATSAPP_PROVIDER_SCOPE_MISMATCH）。Business App 空间不受此限制。确认要继续吗？';

const SCOPE_TYPE_OPTIONS: { value: AdminCamsScopeType; label: string }[] = [
  { value: 'ENTERPRISE_API', label: templateScopeTypeLabels.ENTERPRISE_API },
  { value: 'EMPLOYEE_BUSINESS_APP', label: templateScopeTypeLabels.EMPLOYEE_BUSINESS_APP },
];

export default function AdminPlatformsPage() {
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<AdminCamsScope | null>(null);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<AdminCamsRequest>();
  const chosenType = Form.useWatch('scopeType', form) ?? 'ENTERPRISE_API';
  const scopes = useQuery({ queryKey: ['admin-cams'], queryFn: fetchAdminCams, retry: false });
  // A Business App space must name the employee who authorized it, so the form needs the user list.
  const users = useQuery({ queryKey: ['admin-users', 'owner-picker'], queryFn: () => fetchAdminUsers(0, 100), retry: false });
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['admin-cams'] });
  const save = useMutation({
    mutationFn: (values: AdminCamsRequest) => editing ? updateAdminCams(editing.scopeId, { ...values, expectedVersion: editing.version }) : createAdminCams(values),
    onSuccess: () => { message.success(editing ? 'CAMS 配置已更新' : 'CAMS 配置已添加'); setOpen(false); setEditing(null); form.resetFields(); refresh(); },
    onError: (error) => message.error(errorText(error)),
  });
  const block = useMutation({
    mutationFn: (scope: AdminCamsScope) => blockAdminCams(scope.scopeId, scope.version),
    onSuccess: () => { message.success('CAMS 已停用'); refresh(); },
    onError: (error) => message.error(errorText(error)),
  });
  const test = useMutation({
    mutationFn: (scopeId: string) => testAdminCams(scopeId),
    onSuccess: (result) => { message.success(`${result.message}，发现 ${result.phoneCount} 个号码`); refresh(); },
    onError: (error) => message.error(errorText(error)),
  });
  const sync = useMutation({
    mutationFn: (scopeId: string) => syncAdminCams(scopeId),
    onSuccess: (result) => {
      const summary = camsSyncSummary(result);
      if (hasUnimportedProviderPhones(result)) message.warning(summary); else message.success(summary);
      refresh();
    },
    onError: (error) => message.error(errorText(error)),
  });

  useEffect(() => {
    if (!editing) return;
    form.setFieldsValue({ displayName: editing.displayName, custSpaceId: editing.custSpaceId, accessKeyId: '', accessKeySecret: '', region: editing.region, endpoint: editing.endpoint, scopeType: editing.scopeType, ownerUserId: editing.ownerUserId });
  }, [editing, form]);

  // A second ENTERPRISE_API scope leaves account binding unable to cross spaces, so creating one is
  // gated behind an explicit acknowledgement. A Business App space carries its own owner and does
  // not raise the question.
  const submit = (values: AdminCamsRequest) => {
    const needsWarning = (values.scopeType ?? 'ENTERPRISE_API') === 'ENTERPRISE_API';
    if (editing || !needsWarning || (scopes.data ?? []).length === 0) { save.mutate(values); return; }
    modal.confirm({
      title: SECOND_CAMS_TITLE,
      content: SECOND_CAMS_BODY,
      okText: '确认添加',
      cancelText: '暂不添加',
      okButtonProps: { danger: true },
      onOk: () => { save.mutate(values); },
    });
  };

  const openCreate = () => { setEditing(null); form.resetFields(); form.setFieldsValue({ region: 'ap-southeast-1', endpoint: 'cams.ap-southeast-1.aliyuncs.com', scopeType: 'ENTERPRISE_API', ownerUserId: null }); setOpen(true); };
  const openEdit = (scope: AdminCamsScope) => { setEditing(scope); setOpen(true); };

  const columns = [
    { title: '平台', key: 'name', render: (_: unknown, scope: AdminCamsScope) => <Space direction="vertical" size={0}><Text strong>{scope.displayName}</Text><Text type="secondary">{scope.custSpaceId}</Text></Space> },
    { title: '类型', key: 'scopeType', render: (_: unknown, scope: AdminCamsScope) => <Tag color={scope.scopeType === 'EMPLOYEE_BUSINESS_APP' ? 'blue' : 'default'}>{templateScopeTypeLabels[scope.scopeType] ?? scope.scopeType}</Tag> },
    { title: '状态', dataIndex: 'status', key: 'status', render: (status: string) => <Tag color={status === 'READY' ? 'green' : 'default'}>{status === 'READY' ? '可用' : '已停用'}</Tag> },
    { title: '凭证', key: 'credentials', render: (_: unknown, scope: AdminCamsScope) => <Text type="secondary">{scope.accessKeyIdMasked || '未配置'}</Text> },
    { title: '连接测试', key: 'test', render: (_: unknown, scope: AdminCamsScope) => <Text type="secondary">{scope.lastTestStatus === 'SUCCESS' ? '成功' : scope.lastTestStatus === 'FAILED' ? `失败${scope.lastTestErrorCode ? `（${scope.lastTestErrorCode}）` : ''}` : '未测试'}</Text> },
    { title: '最近同步', key: 'sync', render: (_: unknown, scope: AdminCamsScope) => <Text type="secondary">{scope.lastSyncStatus === 'SUCCESS' && scope.lastSyncedAt ? new Date(scope.lastSyncedAt).toLocaleString('zh-CN') : scope.lastSyncStatus === 'FAILED' ? '失败' : '未同步'}</Text> },
    { title: '操作', key: 'actions', render: (_: unknown, scope: AdminCamsScope) => <Space size="small" wrap><Button size="small" icon={<EditOutlined />} onClick={() => openEdit(scope)}>编辑</Button><Button size="small" icon={<ApiOutlined />} loading={test.isPending && test.variables === scope.scopeId} disabled={scope.status !== 'READY'} onClick={() => test.mutate(scope.scopeId)}>测试</Button><Button size="small" icon={<ReloadOutlined />} loading={sync.isPending && sync.variables === scope.scopeId} disabled={scope.status !== 'READY'} onClick={() => sync.mutate(scope.scopeId)}>同步</Button>{scope.status === 'READY' ? <Popconfirm title="停用 CAMS" description="停用后不会删除历史账号和模板。" okText="停用" cancelText="取消" onConfirm={() => block.mutate(scope)}><Button size="small" danger icon={<StopOutlined />}>停用</Button></Popconfirm> : null}</Space> },
  ];

  return <div style={{ maxWidth: 1180, margin: '0 auto', padding: 24 }}>
    <Space align="center" style={{ width: '100%', justifyContent: 'space-between', marginBottom: 20 }}><div><Title level={3} style={{ margin: 0 }}>平台接入管理</Title><Text type="secondary">管理多个 CAMS 空间及其连接状态</Text></div><Space><Button icon={<ReloadOutlined />} onClick={refresh}>刷新</Button><Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>添加 CAMS</Button></Space></Space>
    {scopes.isError ? <Alert type="error" showIcon message={errorText(scopes.error)} /> : null}
    <Table<AdminCamsScope> rowKey="scopeId" loading={scopes.isLoading} dataSource={scopes.data ?? []} columns={columns} pagination={false} locale={{ emptyText: '暂无 CAMS 配置' }} />
    <Modal title={editing ? '编辑 CAMS' : '添加 CAMS'} open={open} onCancel={() => { setOpen(false); setEditing(null); form.resetFields(); }} onOk={() => form.submit()} confirmLoading={save.isPending} destroyOnHidden width={620}>
      <Form form={form} layout="vertical" onFinish={submit}>
        <Form.Item name="displayName" label="显示名称" rules={[{ required: true, message: '请输入显示名称' }]}><Input placeholder="例如：东南亚销售空间" /></Form.Item>
        <Form.Item name="custSpaceId" label="CustSpaceId" rules={[{ required: true, message: '请输入 CustSpaceId' }]}><Input /></Form.Item>
        <Space align="start" style={{ width: '100%' }} wrap>
          <Form.Item name="scopeType" label="空间类型" rules={[{ required: true, message: '请选择空间类型' }]}><Select options={SCOPE_TYPE_OPTIONS} style={{ width: 220 }} /></Form.Item>
          <Form.Item name="ownerUserId" label="归属人" tooltip="仅 Business App 空间需要，模板变更不再走管理员审批" rules={[{ required: chosenType === 'EMPLOYEE_BUSINESS_APP', message: '请选择归属人' }]}><Select allowClear showSearch optionFilterProp="label" disabled={chosenType !== 'EMPLOYEE_BUSINESS_APP'} loading={users.isLoading} style={{ width: 260 }} placeholder={chosenType === 'EMPLOYEE_BUSINESS_APP' ? '选择员工' : '企业空间无需归属人'} options={(users.data?.items ?? []).map((user) => ({ value: user.id, label: user.displayName || user.username }))} /></Form.Item>
        </Space>
        <Form.Item name="accessKeyId" label="AccessKey ID" rules={[{ required: !editing, message: '请输入 AccessKey ID' }]}><Input placeholder={editing ? `留空保留 ${editing.accessKeyIdMasked}` : 'LTAI…'} /></Form.Item>
        <Form.Item name="accessKeySecret" label="AccessKey Secret" rules={[{ required: !editing, message: '请输入 AccessKey Secret' }]}><Input.Password placeholder={editing ? '留空保留原 Secret' : '仅保存时填写'} /></Form.Item>
        <Space align="start" style={{ width: '100%' }}><Form.Item name="region" label="Region" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="endpoint" label="Endpoint" rules={[{ required: true }]}><Input style={{ width: 330 }} /></Form.Item></Space>
      </Form>
    </Modal>
  </div>;
}
