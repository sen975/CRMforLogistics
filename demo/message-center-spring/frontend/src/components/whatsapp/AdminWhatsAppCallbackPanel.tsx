import { useEffect, useState } from 'react';
import { Alert, App, Button, Divider, Form, Input, Space, Switch, Table, Tag, Typography } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  fetchAdminWhatsAppCallbacks,
  updateAdminWhatsAppAccountCallback,
  updateAdminWhatsAppPhoneCallback,
} from '../../api/endpoints';
import type {
  AdminWhatsAppAccountCallbackConfig,
  AdminWhatsAppCallbackConfig,
  AdminWhatsAppPhoneCallbackConfig,
  WhatsAppCallbackFlag,
} from '../../api/types';

const { Text, Title } = Typography;

function errorStatus(error: unknown): number | undefined {
  const status = (error as { response?: { status?: unknown } })?.response?.status;
  return typeof status === 'number' ? status : undefined;
}

function applyStatus(config: { lastApplyStatus: string; lastErrorCode: string | null }) {
  if (config.lastApplyStatus === 'APPLYING') return <Tag color="processing">正在写入</Tag>;
  if (config.lastApplyStatus === 'SUBMISSION_UNKNOWN') {
    return <Space size={4} wrap><Tag color="orange">写入结果未知</Tag>{config.lastErrorCode ? <Text type="secondary">{config.lastErrorCode}</Text> : null}</Space>;
  }
  if (config.lastApplyStatus === 'FAILED') {
    return <Space size={4} wrap><Tag color="red">最近写入失败</Tag>{config.lastErrorCode ? <Text type="secondary">{config.lastErrorCode}</Text> : null}</Space>;
  }
  if (config.lastApplyStatus === 'SUCCEEDED') return <Tag color="green">最近写入成功</Tag>;
  return <Tag>尚未写入</Tag>;
}

function flag(checked: boolean): WhatsAppCallbackFlag {
  return checked ? 'Y' : 'N';
}

interface EditorFeedback {
  applied(result: AdminWhatsAppCallbackConfig): void;
  failed(error: unknown): Promise<void>;
}

function AccountCallbackEditor({
  scopeId,
  config,
  feedback,
}: {
  scopeId: string;
  config: AdminWhatsAppAccountCallbackConfig;
  feedback: EditorFeedback;
}) {
  const [statusUrl, setStatusUrl] = useState(config.desiredStatusCallbackUrl ?? '');
  const [httpEnabled, setHttpEnabled] = useState(config.httpFlag === 'Y');
  const [queueEnabled, setQueueEnabled] = useState(config.queueFlag === 'Y');
  useEffect(() => {
    setStatusUrl(config.desiredStatusCallbackUrl ?? '');
    setHttpEnabled(config.httpFlag === 'Y');
    setQueueEnabled(config.queueFlag === 'Y');
  }, [config]);
  const update = useMutation({
    mutationFn: () => updateAdminWhatsAppAccountCallback(scopeId, {
      statusCallbackUrl: statusUrl.trim(),
      httpFlag: flag(httpEnabled),
      queueFlag: flag(queueEnabled),
      expectedVersion: config.version,
    }),
    onSuccess: feedback.applied,
    onError: feedback.failed,
  });

  return <Form layout="vertical" onFinish={() => update.mutate()}>
    <Space align="end" wrap size="middle" style={{ width: '100%' }}>
      <Form.Item label="状态回执地址" style={{ flex: '1 1 420px', marginBottom: 0 }}>
        <Input
          aria-label="账户级状态回执地址"
          type="url"
          value={statusUrl}
          maxLength={2048}
          placeholder="https://"
          onChange={(event) => setStatusUrl(event.target.value)}
        />
      </Form.Item>
      <Form.Item label="HTTP" style={{ marginBottom: 0 }}>
        <Switch aria-label="账户级 HTTP 回调" checked={httpEnabled} onChange={setHttpEnabled} />
      </Form.Item>
      <Form.Item label="队列" style={{ marginBottom: 0 }}>
        <Switch aria-label="账户级队列回调" checked={queueEnabled} onChange={setQueueEnabled} />
      </Form.Item>
      <Button
        htmlType="submit"
        icon={<SaveOutlined aria-hidden="true" />}
        aria-label="保存账户回调"
        loading={update.isPending}
      >保存</Button>
      {applyStatus(config)}
    </Space>
  </Form>;
}

function PhoneCallbackEditor({
  scopeId,
  config,
  feedback,
}: {
  scopeId: string;
  config: AdminWhatsAppPhoneCallbackConfig;
  feedback: EditorFeedback;
}) {
  const [upUrl, setUpUrl] = useState(config.desiredUpCallbackUrl ?? '');
  const [statusUrl, setStatusUrl] = useState(config.desiredStatusCallbackUrl ?? '');
  const [httpEnabled, setHttpEnabled] = useState(config.httpFlag === 'Y');
  const [queueEnabled, setQueueEnabled] = useState(config.queueFlag === 'Y');
  useEffect(() => {
    setUpUrl(config.desiredUpCallbackUrl ?? '');
    setStatusUrl(config.desiredStatusCallbackUrl ?? '');
    setHttpEnabled(config.httpFlag === 'Y');
    setQueueEnabled(config.queueFlag === 'Y');
  }, [config]);
  const update = useMutation({
    mutationFn: () => updateAdminWhatsAppPhoneCallback(scopeId, config.channelAccountId, {
      upCallbackUrl: upUrl.trim(),
      statusCallbackUrl: statusUrl.trim(),
      httpFlag: flag(httpEnabled),
      queueFlag: flag(queueEnabled),
      expectedVersion: config.version,
    }),
    onSuccess: feedback.applied,
    onError: feedback.failed,
  });

  return <Space direction="vertical" size="small" style={{ width: '100%' }}>
    <Input aria-label="上行消息地址" type="url" value={upUrl} maxLength={2048} placeholder="https://" onChange={(event) => setUpUrl(event.target.value)} />
    <Input aria-label="状态回执地址" type="url" value={statusUrl} maxLength={2048} placeholder="https://" onChange={(event) => setStatusUrl(event.target.value)} />
    <Space wrap>
      <Space size={4}><Text type="secondary">HTTP</Text><Switch size="small" aria-label="号码级 HTTP 回调" checked={httpEnabled} onChange={setHttpEnabled} /></Space>
      <Space size={4}><Text type="secondary">队列</Text><Switch size="small" aria-label="号码级队列回调" checked={queueEnabled} onChange={setQueueEnabled} /></Space>
      <Button
        size="small"
        icon={<SaveOutlined aria-hidden="true" />}
        aria-label="保存号码回调"
        loading={update.isPending}
        onClick={() => update.mutate()}
      >保存</Button>
      {applyStatus(config)}
    </Space>
  </Space>;
}

export function AdminWhatsAppCallbackPanel({ scopeId, isAdmin }: { scopeId: string; isAdmin: boolean }) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const queryKey = ['adminWhatsAppCallbacks', scopeId] as const;
  const callbacks = useQuery({
    queryKey,
    queryFn: () => fetchAdminWhatsAppCallbacks(scopeId),
    enabled: isAdmin && Boolean(scopeId),
    retry: false,
  });
  if (!isAdmin) return null;

  const feedback: EditorFeedback = {
    applied: (result) => {
      queryClient.setQueryData(queryKey, result);
      message.success('回调配置已写入 CAMS');
    },
    failed: async (error) => {
      if (errorStatus(error) === 409) {
        await callbacks.refetch();
        message.error('配置版本已变化，已重新加载');
        return;
      }
      message.error('回调配置写入失败');
    },
  };
  const accountConfig: AdminWhatsAppAccountCallbackConfig = callbacks.data?.accountConfig ?? {
    desiredStatusCallbackUrl: null,
    httpFlag: 'Y',
    queueFlag: 'N',
    providerState: 'UNKNOWN',
    lastApplyStatus: 'NEVER_APPLIED',
    lastErrorCode: null,
    lastAppliedAt: null,
    version: 0,
  };

  return <section aria-label="CAMS 回调地址" style={{ marginTop: 28 }}>
    <Title level={4} style={{ marginBottom: 4 }}>CAMS 回调地址</Title>
    <Text type="secondary">配置期望地址和最近写入结果；CAMS 暂无官方回读接口，服务商状态显示为未回读。</Text>
    {callbacks.isError ? <Alert type="error" showIcon message="回调配置加载失败" style={{ marginTop: 16 }} /> : null}
    <Divider orientation="left">账户级状态回执</Divider>
    <AccountCallbackEditor scopeId={scopeId} config={accountConfig} feedback={feedback} />
    <Divider orientation="left">号码级回调</Divider>
    <Table<AdminWhatsAppPhoneCallbackConfig>
      rowKey="channelAccountId"
      size="small"
      loading={callbacks.isLoading}
      dataSource={callbacks.data?.phoneConfigs ?? []}
      pagination={false}
      scroll={{ x: 860 }}
      locale={{ emptyText: '当前 CAMS 暂无可配置号码' }}
      columns={[
        { title: '号码', dataIndex: 'maskedPhone', width: 150 },
        {
          title: '地址与写入状态',
          key: 'editor',
          render: (_value, config) => <PhoneCallbackEditor scopeId={scopeId} config={config} feedback={feedback} />,
        },
      ]}
    />
  </section>;
}
