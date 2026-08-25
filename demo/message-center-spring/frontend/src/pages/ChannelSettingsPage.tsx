import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Table, Tag, Button, Typography, App, Space, Input, Spin, Empty, Descriptions, Modal, Form, Switch } from 'antd';
import {
  MailOutlined,
  MessageOutlined,
  WechatOutlined,
  SyncOutlined,
  CheckOutlined,
  CloseOutlined,
  EditOutlined,
  KeyOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  fetchChannelAccounts,
  updateChannelAccount,
  triggerChannelSync,
  fetchChannelCredentials,
  updateChannelCredentials,
} from '../api/endpoints';
import type { ChannelAccount, ChannelCredentials } from '../api/types';

const { Title, Text } = Typography;

const channelIcons: Record<string, React.ReactNode> = {
  email: <MailOutlined />,
  chatapp: <MessageOutlined />,
  wecom: <WechatOutlined />,
};

const channelLabels: Record<string, string> = {
  email: '邮件',
  chatapp: 'ChatApp',
  wecom: '企业微信',
};

function formatRelativeTime(iso: string | null): string {
  if (!iso) return '从未同步';
  const diff = Date.now() - new Date(iso).getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  return `${days} 天前`;
}

const credentialFields: Record<string, { key: string; label: string; secret?: boolean; type?: string }[]> = {
  email: [
    { key: 'smtpHost', label: 'SMTP 主机' },
    { key: 'smtpPort', label: 'SMTP 端口' },
    { key: 'smtpSsl', label: 'SMTP SSL', type: 'switch' },
    { key: 'smtpUser', label: 'SMTP 用户名' },
    { key: 'smtpPassword', label: 'SMTP 密码', secret: true },
    { key: 'imapHost', label: 'IMAP 主机' },
    { key: 'imapPort', label: 'IMAP 端口' },
    { key: 'imapSsl', label: 'IMAP SSL', type: 'switch' },
    { key: 'imapUser', label: 'IMAP 用户名' },
    { key: 'imapPassword', label: 'IMAP 密码', secret: true },
    { key: 'mailProvider', label: '邮件服务商' },
    { key: 'mailFrom', label: '发件人地址' },
  ],
  chatapp: [
    { key: 'accessKeyId', label: 'AccessKey ID' },
    { key: 'accessKeySecret', label: 'AccessKey Secret', secret: true },
    { key: 'camsRegion', label: 'CAMS 区域' },
    { key: 'camsEndpoint', label: 'CAMS 端点' },
    { key: 'custSpaceId', label: 'CustSpace ID' },
    { key: 'chatappFrom', label: '发送号码' },
  ],
};

type EditingField = 'name' | 'identifier' | null;

export default function ChannelSettingsPage() {
  const qc = useQueryClient();
  const { message } = App.useApp();
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingField, setEditingField] = useState<EditingField>(null);
  const [editValue, setEditValue] = useState('');
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);

  const { data: accounts, isLoading } = useQuery({
    queryKey: ['channelAccounts'],
    queryFn: fetchChannelAccounts,
  });

  const syncMutation = useMutation({
    mutationFn: (id: string) => triggerChannelSync(id),
    onSuccess: () => {
      message.success('同步已触发');
      qc.invalidateQueries({ queryKey: ['channelAccounts'] });
      qc.invalidateQueries({ queryKey: ['contacts'] });
    },
    onError: () => message.error('同步失败'),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, name, accountIdentifier }: { id: string; name?: string; accountIdentifier?: string }) =>
      updateChannelAccount(id, { name, accountIdentifier }),
    onSuccess: () => {
      message.success('已更新');
      qc.invalidateQueries({ queryKey: ['channelAccounts'] });
      setEditingId(null);
      setEditingField(null);
    },
    onError: () => message.error('更新失败'),
  });

  const [credentialModalId, setCredentialModalId] = useState<string | null>(null);
  const [credentialForm] = Form.useForm();

  const { data: credentials, isLoading: credsLoading } = useQuery({
    queryKey: ['channelCredentials', credentialModalId],
    queryFn: () => fetchChannelCredentials(credentialModalId!),
    enabled: !!credentialModalId,
  });

  useEffect(() => {
    if (credentials && credentialModalId) {
      const fields: Record<string, string | boolean> = {};
      for (const [k, v] of Object.entries(credentials)) {
        fields[k] = v === 'true' ? true : v === 'false' ? false : (v || '');
      }
      credentialForm.setFieldsValue(fields);
    }
  }, [credentials, credentialModalId, credentialForm]);

  const credsMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: ChannelCredentials }) =>
      updateChannelCredentials(id, data),
    onSuccess: () => {
      message.success('凭证已更新');
      setCredentialModalId(null);
      credentialForm.resetFields();
      qc.invalidateQueries({ queryKey: ['channelCredentials'] });
    },
    onError: () => message.error('凭证更新失败'),
  });

  const startEdit = (id: string, field: EditingField, value: string) => {
    setEditingId(id);
    setEditingField(field);
    setEditValue(value);
  };

  const saveEdit = (id: string) => {
    if (editingField === 'name') {
      updateMutation.mutate({ id, name: editValue });
    } else if (editingField === 'identifier') {
      updateMutation.mutate({ id, accountIdentifier: editValue });
    }
  };

  const cancelEdit = () => {
    setEditingId(null);
    setEditingField(null);
  };

  const renderEditableCell = (record: ChannelAccount, field: EditingField, currentValue: string, display: React.ReactNode) => {
    if (editingId === record.id && editingField === field) {
      return (
        <Space>
          <Input
            size="small"
            value={editValue}
            onChange={(e) => setEditValue(e.target.value)}
            style={{ width: 180 }}
            autoFocus
            onPressEnter={() => saveEdit(record.id)}
          />
          <Button
            size="small"
            type="primary"
            icon={<CheckOutlined />}
            loading={updateMutation.isPending}
            onClick={() => saveEdit(record.id)}
          />
          <Button size="small" icon={<CloseOutlined />} onClick={cancelEdit} />
        </Space>
      );
    }
    return (
      <Space>
        {display}
        <Button
          type="link"
          size="small"
          icon={<EditOutlined />}
          onClick={() => startEdit(record.id, field, currentValue)}
        />
      </Space>
    );
  };

  const columns = [
    {
      title: '渠道',
      key: 'channel',
      width: 200,
      render: (_: unknown, record: ChannelAccount) => {
        const nameDisplay = (
          <Space>
            <span>{channelIcons[record.channelType] ?? <MessageOutlined />}</span>
            <Text strong>{record.name}</Text>
          </Space>
        );
        return renderEditableCell(record, 'name', record.name, nameDisplay);
      },
    },
    {
      title: '类型',
      dataIndex: 'channelType',
      key: 'channelType',
      width: 100,
      render: (t: string) => <Tag>{channelLabels[t] ?? t}</Tag>,
    },
    {
      title: '账号标识',
      dataIndex: 'accountIdentifier',
      key: 'accountIdentifier',
      ellipsis: true,
      render: (v: string, record: ChannelAccount) => {
        const display = <Text type="secondary" style={{ fontSize: 13 }}>{v}</Text>;
        return renderEditableCell(record, 'identifier', v, display);
      },
    },
    {
      title: '认证状态',
      dataIndex: 'authStatus',
      key: 'authStatus',
      width: 100,
      render: (s: string) => {
        const map: Record<string, { label: string; color: string }> = {
          active: { label: '已认证', color: 'green' },
          expired: { label: '已过期', color: 'orange' },
          failed: { label: '失败', color: 'red' },
          disabled: { label: '已禁用', color: 'default' },
          unbound: { label: '未绑定', color: 'default' },
        };
        const info = map[s] ?? { label: s, color: 'default' };
        return <Tag color={info.color}>{info.label}</Tag>;
      },
    },
    {
      title: '上次同步',
      dataIndex: 'lastSyncedAt',
      key: 'lastSyncedAt',
      width: 110,
      render: (v: string | null) => (
        <Text type="secondary" style={{ fontSize: 12 }}>
          {formatRelativeTime(v)}
        </Text>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_: unknown, record: ChannelAccount) => (
        <Space>
          <Button
            size="small"
            icon={<SyncOutlined />}
            loading={syncMutation.isPending && syncMutation.variables === record.id}
            onClick={() => syncMutation.mutate(record.id)}
          >
            同步
          </Button>
          {record.channelType === 'wecom' && (
            <Link to="/settings/wecom">
              <Button size="small" icon={<SettingOutlined />} aria-label="管理企业微信">管理</Button>
            </Link>
          )}
        </Space>
      ),
    },
  ];

  const expandedRowRender = (record: ChannelAccount) => (
    <div>
      <Descriptions size="small" column={2} bordered style={{ marginBottom: 12 }}>
        <Descriptions.Item label="渠道类型">{channelLabels[record.channelType] ?? record.channelType}</Descriptions.Item>
        <Descriptions.Item label="同步状态">
          <Tag color={record.syncStatus === 'syncing' ? 'processing' : record.syncStatus === 'failed' ? 'red' : 'default'}>
            {record.syncStatus || 'idle'}
          </Tag>
        </Descriptions.Item>
        <Descriptions.Item label="创建时间">
          {record.createdAt ? new Date(record.createdAt).toLocaleString('zh-CN') : '-'}
        </Descriptions.Item>
      </Descriptions>
      <Button icon={<KeyOutlined />} onClick={() => setCredentialModalId(record.id)}>
        编辑凭证
      </Button>
    </div>
  );

  return (
    <div style={{ maxWidth: 900, margin: '0 auto', padding: 24 }}>
      <Title level={4}>渠道设置</Title>
      {isLoading ? (
        <div style={{ textAlign: 'center', padding: 48 }}>
          <Spin />
        </div>
      ) : !accounts || accounts.length === 0 ? (
        <Empty description="暂无渠道账号" />
      ) : (
        <Table
          dataSource={accounts}
          rowKey="id"
          columns={columns}
          pagination={false}
          size="small"
          expandable={{
            expandedRowRender,
            rowExpandable: () => true,
            expandedRowKeys: expandedKeys,
            onExpandedRowsChange: (keys) => setExpandedKeys(keys as string[]),
          }}
        />
      )}
      <Modal
        title="编辑凭证"
        open={!!credentialModalId}
        onCancel={() => { setCredentialModalId(null); credentialForm.resetFields(); }}
        onOk={() => credentialForm.submit()}
        confirmLoading={credsMutation.isPending}
        width={520}
      >
        <Spin spinning={credsLoading}>
          <Form
            form={credentialForm}
            layout="vertical"
            onFinish={(values: Record<string, string | boolean>) => {
              const data: ChannelCredentials = {};
              for (const [k, v] of Object.entries(values)) {
                data[k] = typeof v === 'boolean' ? String(v) : (v || '');
              }
              credsMutation.mutate({ id: credentialModalId!, data });
            }}
          >
            {(credentialFields[accounts?.find(a => a.id === credentialModalId)?.channelType ?? ''] ?? []).map((field) => (
              <Form.Item key={field.key} name={field.key} label={field.label}>
                {field.type === 'switch' ? (
                  <Switch />
                ) : (
                  <Input.Password
                    placeholder={field.secret ? '留空则不修改' : undefined}
                    iconRender={field.secret ? undefined : () => null}
                  />
                )}
              </Form.Item>
            ))}
          </Form>
        </Spin>
      </Modal>
    </div>
  );
}
