import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { App, Button, Descriptions, Form, Input, Modal, Popconfirm, Space, Spin, Switch, Table, Tag, Typography } from 'antd';
import { CheckOutlined, CloseOutlined, EditOutlined, KeyOutlined, MailOutlined, SettingOutlined, SyncOutlined, WechatOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createChannelAccount, fetchChannelAccounts, fetchChannelCredentials, fetchWeComBinding, triggerChannelSync, unbindChannelAccount, updateChannelAccount, updateChannelCredentials } from '../api/endpoints';
import type { ChannelAccount, ChannelCredentials, CreateChannelAccountRequest } from '../api/types';
import { WeComBindingPanel } from '../components/wecom/WeComBindingPanel';
import { AdminWhatsAppAccountPanel } from '../components/whatsapp/AdminWhatsAppAccountPanel';
import { useAuth } from '../hooks/useAuth';

const { Title, Text } = Typography;
const channelIcons: Record<string, React.ReactNode> = { email: <MailOutlined />, wecom: <WechatOutlined /> };
const channelLabels: Record<string, string> = { email: '邮件', wecom: '企业微信' };
const configurableChannelTypes = ['email'] as const;
type ConfigurableChannelType = typeof configurableChannelTypes[number];
type ChannelRow = ChannelAccount & { configured: boolean };

const credentialFields: Record<ConfigurableChannelType, { key: string; label: string; secret?: boolean; type?: 'switch' }[]> = {
  email: [
    { key: 'smtpHost', label: 'SMTP 主机' }, { key: 'smtpPort', label: 'SMTP 端口' }, { key: 'smtpSsl', label: 'SMTP SSL', type: 'switch' },
    { key: 'smtpUser', label: 'SMTP 用户名' }, { key: 'smtpPassword', label: 'SMTP 密码', secret: true },
    { key: 'imapHost', label: 'IMAP 主机' }, { key: 'imapPort', label: 'IMAP 端口' }, { key: 'imapSsl', label: 'IMAP SSL', type: 'switch' },
    { key: 'imapUser', label: 'IMAP 用户名' }, { key: 'imapPassword', label: 'IMAP 密码', secret: true },
    { key: 'provider', label: '邮件服务商' }, { key: 'mailFrom', label: '发件人地址' },
  ],
};

function formatRelativeTime(iso: string | null): string {
  if (!iso) return '从未同步';
  const minutes = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours} 小时前` : `${Math.floor(hours / 24)} 天前`;
}

function unconfiguredRow(channelType: 'email' | 'wecom'): ChannelRow {
  return { id: `unconfigured-${channelType}`, channelType, name: channelLabels[channelType], accountIdentifier: '', authStatus: 'unbound', syncStatus: 'idle', lastSyncedAt: null, createdAt: '', configured: false };
}

function displayIdentifier(record: ChannelRow): string {
  return record.accountIdentifier || '-';
}

function authStatusTag(status: string) {
  const map: Record<string, { label: string; color: string }> = {
    active: { label: '已认证', color: 'green' }, expired: { label: '已过期', color: 'orange' },
    failed: { label: '失败', color: 'red' }, disabled: { label: '已禁用', color: 'default' }, unbound: { label: '未配置', color: 'default' },
  };
  const info = map[status] ?? { label: status, color: 'default' };
  return <Tag color={info.color}>{info.label}</Tag>;
}

export default function ChannelSettingsPage() {
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const { isAdmin } = useAuth();
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');
  const [credentialModalId, setCredentialModalId] = useState<string | null>(null);
  const [creatingChannel, setCreatingChannel] = useState<ConfigurableChannelType | null>(null);
  const [weComAuthorizationOpen, setWeComAuthorizationOpen] = useState(false);
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const [credentialForm] = Form.useForm();
  const [createForm] = Form.useForm();
  const { data: accounts, isLoading } = useQuery({ queryKey: ['channelAccounts'], queryFn: fetchChannelAccounts });
  const { data: weComBinding } = useQuery({ queryKey: ['wecom-binding'], queryFn: fetchWeComBinding });

  const configuredRows = configurableChannelTypes.map((channelType) => {
    const account = accounts?.find((item) => item.channelType === channelType);
    return account ? { ...account, channelType, configured: true } : unconfiguredRow(channelType);
  });
  const weComRow: ChannelRow = weComBinding?.bound
    ? { id: 'wecom-binding', channelType: 'wecom', name: channelLabels.wecom, accountIdentifier: weComBinding.wecomDisplayName?.trim() || '已绑定企业微信', authStatus: 'active', syncStatus: 'idle', lastSyncedAt: null, createdAt: '', configured: true }
    : unconfiguredRow('wecom');
  const rows = [...configuredRows, weComRow];

  const invalidateAccounts = () => void queryClient.invalidateQueries({ queryKey: ['channelAccounts'] });
  const syncMutation = useMutation({ mutationFn: triggerChannelSync, onSuccess: () => { message.success('同步已触发'); invalidateAccounts(); void queryClient.invalidateQueries({ queryKey: ['contacts'] }); }, onError: () => message.error('同步失败') });
  const updateMutation = useMutation({ mutationFn: ({ id, name }: { id: string; name: string }) => updateChannelAccount(id, { name }), onSuccess: () => { message.success('已更新'); invalidateAccounts(); setEditingId(null); }, onError: () => message.error('更新失败') });
  const unbindMutation = useMutation({ mutationFn: unbindChannelAccount, onSuccess: () => { message.success('已解绑'); invalidateAccounts(); }, onError: () => message.error('解绑失败') });
  const createMutation = useMutation({ mutationFn: createChannelAccount, onSuccess: () => { message.success('渠道已配置'); setCreatingChannel(null); createForm.resetFields(); invalidateAccounts(); }, onError: () => message.error('渠道配置失败') });
  const { data: credentials, isLoading: credentialsLoading } = useQuery({ queryKey: ['channelCredentials', credentialModalId], queryFn: () => fetchChannelCredentials(credentialModalId!), enabled: !!credentialModalId });
  const credentialsMutation = useMutation({ mutationFn: ({ id, data }: { id: string; data: ChannelCredentials }) => updateChannelCredentials(id, data), onSuccess: () => { message.success('凭证已更新'); setCredentialModalId(null); credentialForm.resetFields(); void queryClient.invalidateQueries({ queryKey: ['channelCredentials'] }); }, onError: () => message.error('凭证更新失败') });

  useEffect(() => {
    if (!credentials || !credentialModalId) return;
    credentialForm.setFieldsValue(Object.fromEntries(Object.entries(credentials).map(([key, value]) => [key, value === 'true' ? true : value === 'false' ? false : value || ''])));
  }, [credentialForm, credentialModalId, credentials]);

  const openCreateModal = (channelType: ConfigurableChannelType) => {
    createForm.resetFields();
    createForm.setFieldsValue({ name: channelLabels[channelType], smtpSsl: true, imapSsl: true });
    setCreatingChannel(channelType);
  };
  const credentialChannel = rows.find((row) => row.id === credentialModalId)?.channelType as ConfigurableChannelType | undefined;

  const columns = [
    { title: '渠道', key: 'channel', width: 220, render: (_: unknown, record: ChannelRow) => {
      if (!record.configured || record.channelType === 'wecom') return <Space><span>{channelIcons[record.channelType]}</span><Text strong>{record.name}</Text></Space>;
      if (editingId === record.id) return <Space><Input size="small" value={editValue} onChange={(event) => setEditValue(event.target.value)} autoFocus /><Button size="small" type="primary" icon={<CheckOutlined />} loading={updateMutation.isPending} onClick={() => updateMutation.mutate({ id: record.id, name: editValue })} /><Button size="small" icon={<CloseOutlined />} onClick={() => setEditingId(null)} /></Space>;
      return <Space><span>{channelIcons[record.channelType]}</span><Text strong>{record.name}</Text><Button type="link" size="small" icon={<EditOutlined />} aria-label={`编辑${record.name}`} onClick={() => { setEditingId(record.id); setEditValue(record.name); }} /></Space>;
    } },
    { title: '账号标识', dataIndex: 'accountIdentifier', key: 'accountIdentifier', render: (_value: string, record: ChannelRow) => <Text type="secondary" style={{ fontSize: 13 }}>{displayIdentifier(record)}</Text> },
    { title: '认证状态', dataIndex: 'authStatus', key: 'authStatus', width: 110, render: authStatusTag },
    { title: '上次同步', dataIndex: 'lastSyncedAt', key: 'lastSyncedAt', width: 110, render: (value: string | null, record: ChannelRow) => record.channelType === 'wecom' ? '-' : <Text type="secondary" style={{ fontSize: 12 }}>{formatRelativeTime(value)}</Text> },
    { title: '操作', key: 'action', width: 230, render: (_: unknown, record: ChannelRow) => {
      if (!record.configured) {
        if (record.channelType === 'wecom') return <Button icon={<WechatOutlined />} onClick={() => setWeComAuthorizationOpen(true)}>授权</Button>;
        return <Button type="primary" onClick={() => openCreateModal(record.channelType as ConfigurableChannelType)}>配置</Button>;
      }
      if (record.channelType === 'wecom') return <Link to="/settings/wecom"><Button icon={<SettingOutlined />} aria-label="管理企业微信">管理</Button></Link>;
      return <Space size="small"><Button size="small" icon={<KeyOutlined />} onClick={() => setCredentialModalId(record.id)}>编辑凭证</Button><Button size="small" icon={<SyncOutlined />} loading={syncMutation.isPending && syncMutation.variables === record.id} onClick={() => syncMutation.mutate(record.id)}>同步</Button><Popconfirm title="解绑渠道账号" description="解绑后将停止同步该渠道。" okText="解绑" cancelText="取消" onConfirm={() => unbindMutation.mutate(record.id)}><Button size="small" danger loading={unbindMutation.isPending && unbindMutation.variables === record.id}>解绑</Button></Popconfirm></Space>;
    } },
  ];

  return <div style={{ maxWidth: 980, margin: '0 auto', padding: 24 }}>
    <Title level={4}>渠道设置</Title>
    <AdminWhatsAppAccountPanel isAdmin={isAdmin} />
    {isLoading ? <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div> : <Table dataSource={rows} rowKey="id" columns={columns} pagination={false} size="small" expandable={{ expandedRowKeys: expandedKeys, onExpandedRowsChange: (keys) => setExpandedKeys(keys as string[]), rowExpandable: (record: ChannelRow) => record.configured && record.channelType !== 'wecom', expandedRowRender: (record: ChannelRow) => <Descriptions size="small" column={2} bordered><Descriptions.Item label="渠道类型">{channelLabels[record.channelType]}</Descriptions.Item><Descriptions.Item label="同步状态"><Tag>{record.syncStatus || 'idle'}</Tag></Descriptions.Item><Descriptions.Item label="创建时间">{record.createdAt ? new Date(record.createdAt).toLocaleString('zh-CN') : '-'}</Descriptions.Item></Descriptions> }} />}
    <Modal title={creatingChannel ? `配置${channelLabels[creatingChannel]}` : '配置渠道'} open={!!creatingChannel} onCancel={() => { setCreatingChannel(null); createForm.resetFields(); }} onOk={() => createForm.submit()} confirmLoading={createMutation.isPending} width={560} destroyOnHidden>
      <Form form={createForm} layout="vertical" onFinish={(values: Record<string, string | boolean>) => {
        if (!creatingChannel) return;
        const { name, accountIdentifier, ...credentialValues } = values;
        const credentials: ChannelCredentials = Object.fromEntries(Object.entries(credentialValues).map(([key, value]) => [key, typeof value === 'boolean' ? String(value) : value]));
        createMutation.mutate({ channelType: creatingChannel, name: String(name), accountIdentifier: String(accountIdentifier), credentials } satisfies CreateChannelAccountRequest);
      }}>
        <Form.Item name="name" label="显示名称" rules={[{ required: true, message: '请输入显示名称' }]}><Input /></Form.Item>
        <Form.Item name="accountIdentifier" label={creatingChannel === 'email' ? '邮箱地址' : 'WhatsApp 账号'} rules={[{ required: true, message: '请输入账号标识' }]}><Input /></Form.Item>
        {creatingChannel && credentialFields[creatingChannel].map((field) => <Form.Item key={field.key} name={field.key} label={field.label} valuePropName={field.type === 'switch' ? 'checked' : 'value'} rules={field.type ? undefined : [{ required: true, message: `请输入${field.label}` }]}>{field.type === 'switch' ? <Switch /> : field.secret ? <Input.Password /> : <Input />}</Form.Item>)}
      </Form>
    </Modal>
    <Modal title="授权企业微信" open={weComAuthorizationOpen} footer={null} onCancel={() => setWeComAuthorizationOpen(false)} destroyOnHidden><WeComBindingPanel startBinding onBound={async () => { await queryClient.invalidateQueries({ queryKey: ['wecom-binding'] }); setWeComAuthorizationOpen(false); }} /></Modal>
    <Modal title="编辑凭证" open={!!credentialModalId} onCancel={() => { setCredentialModalId(null); credentialForm.resetFields(); }} onOk={() => credentialForm.submit()} confirmLoading={credentialsMutation.isPending} width={520} destroyOnHidden>
      <Spin spinning={credentialsLoading}><Form form={credentialForm} layout="vertical" onFinish={(values: Record<string, string | boolean>) => credentialsMutation.mutate({ id: credentialModalId!, data: Object.fromEntries(Object.entries(values).map(([key, value]) => [key, typeof value === 'boolean' ? String(value) : value || ''])) })}>{(credentialChannel && credentialFields[credentialChannel] || []).map((field) => <Form.Item key={field.key} name={field.key} label={field.label} valuePropName={field.type === 'switch' ? 'checked' : 'value'}>{field.type === 'switch' ? <Switch /> : field.secret ? <Input.Password placeholder="留空则不修改" /> : <Input />}</Form.Item>)}</Form></Spin>
    </Modal>
  </div>;
}
