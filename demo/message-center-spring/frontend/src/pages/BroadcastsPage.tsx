import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Drawer,
  Descriptions,
  Form,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  type TableColumnsType,
} from 'antd';
import { EyeOutlined, PlusOutlined, ReloadOutlined, WarningOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createChatAppBroadcast,
  fetchChannelCapabilities,
  fetchChatAppBroadcastFailures,
  fetchChatAppBroadcastDetail,
  fetchChatAppBroadcasts,
  fetchChatAppBroadcastTemplates,
  fetchContacts,
  retryChatAppBroadcastFailures,
  requestChatAppBroadcastReconciliation,
} from '../api/endpoints';
import type {
  ChatAppBroadcast,
  ChatAppBroadcastStatus,
  CreateChatAppBroadcastCommand,
  TemplateResponse,
} from '../api/types';
import { useSse } from '../hooks/useSse';
import { contactDisplayName } from '../utils/contactDisplayName';

const { Title, Text } = Typography;

const statusDisplay: Record<ChatAppBroadcastStatus, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' },
  QUEUED: { label: '排队中', color: 'processing' },
  SUBMITTING: { label: '提交中', color: 'processing' },
  SUBMITTED: { label: '已提交', color: 'blue' },
  RECONCILING: { label: '处理中', color: 'processing' },
  SUCCEEDED: { label: '已完成', color: 'success' },
  PARTIALLY_FAILED: { label: '部分失败', color: 'error' },
  FAILED: { label: '发送失败', color: 'error' },
  SUBMISSION_UNKNOWN: { label: '提交结果未知', color: 'warning' },
  STATUS_UNKNOWN: { label: '对账异常', color: 'warning' },
  CANCELLED: { label: '已取消', color: 'default' },
};

interface CreateFormValues {
  name: string;
  templateKey: string;
  identities: Array<{ value: string; label: string }>;
  sharedTemplateParams?: Record<string, string>;
}

function requestId(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function errorMessage(error: unknown): string {
  return (error as { response?: { data?: { message?: string } } }).response?.data?.message
    ?? '操作失败，请稍后重试';
}

function formatTime(value: string | null): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN', { hour12: false });
}

export default function BroadcastsPage() {
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const [form] = Form.useForm<CreateFormValues>();
  const [accountId, setAccountId] = useState<string>();
  const [page, setPage] = useState(1);
  const [createOpen, setCreateOpen] = useState(false);
  const [contactSearch, setContactSearch] = useState('');
  const [failureBroadcast, setFailureBroadcast] = useState<ChatAppBroadcast | null>(null);
  const [detailBroadcast, setDetailBroadcast] = useState<ChatAppBroadcast | null>(null);
  const [failurePage, setFailurePage] = useState(1);
  const createRequestId = useRef(requestId('broadcast'));
  const failureRetryRequestIds = useRef(new Map<string, string>());

  const capabilitiesQuery = useQuery({
    queryKey: ['channelCapabilities'],
    queryFn: fetchChannelCapabilities,
  });
  const accounts = useMemo(() => (capabilitiesQuery.data ?? []).filter((item) => {
    const type = item.channelType.trim().toLowerCase();
    return (type === 'chatapp' || type === 'whatsapp')
      && item.authStatus.trim().toLowerCase() === 'active';
  }), [capabilitiesQuery.data]);

  useEffect(() => {
    if (!accounts.some((account) => account.channelAccountId === accountId)) {
      setAccountId(accounts[0]?.channelAccountId);
      setPage(1);
    }
  }, [accountId, accounts]);

  const broadcastsQuery = useQuery({
    queryKey: ['chatapp-broadcasts', accountId, page],
    queryFn: () => fetchChatAppBroadcasts(accountId!, page, 20),
    enabled: Boolean(accountId),
  });
  const templatesQuery = useQuery({
    queryKey: ['chatapp-broadcast-templates', accountId],
    queryFn: () => fetchChatAppBroadcastTemplates(accountId!),
    enabled: Boolean(accountId && createOpen),
  });
  const contactsQuery = useQuery({
    queryKey: ['contacts', 'chatapp-broadcast', accountId, contactSearch],
    queryFn: () => fetchContacts({
      search: contactSearch || undefined,
      page: 1,
      size: 100,
      channelType: 'chatapp',
      channelAccountId: accountId,
    }),
    enabled: Boolean(accountId && createOpen),
  });
  const failuresQuery = useQuery({
    queryKey: ['chatapp-broadcast-failures', failureBroadcast?.id, failurePage],
    queryFn: () => fetchChatAppBroadcastFailures(failureBroadcast!.id, failurePage, 20),
    enabled: Boolean(failureBroadcast),
  });
  const detailQuery = useQuery({
    queryKey: ['chatapp-broadcast-detail', detailBroadcast?.id],
    queryFn: () => fetchChatAppBroadcastDetail(detailBroadcast!.id),
    enabled: Boolean(detailBroadcast),
  });

  useSse(() => {
    void queryClient.invalidateQueries({ queryKey: ['chatapp-broadcasts'] });
    if (failureBroadcast) {
      void queryClient.invalidateQueries({
        queryKey: ['chatapp-broadcast-failures', failureBroadcast.id],
      });
    }
    if (detailBroadcast) {
      void queryClient.invalidateQueries({
        queryKey: ['chatapp-broadcast-detail', detailBroadcast.id],
      });
    }
  });

  const selectedTemplateKey = Form.useWatch('templateKey', form);
  const selectedTemplate = useMemo(() => (templatesQuery.data ?? []).find(
    (template) => `${template.templateCode}::${template.languageCode}` === selectedTemplateKey,
  ), [selectedTemplateKey, templatesQuery.data]);

  const contactOptions = useMemo(() => (contactsQuery.data?.records ?? []).flatMap((contact) =>
    contact.identities
      .filter((identity) => identity.channelType.toLowerCase() === 'chatapp'
        && identity.identityScope === accountId)
      .map((identity) => ({
        value: identity.id,
        label: contactDisplayName({
          remark: contact.remark,
          displayName: contact.displayName || identity.displayName || identity.identityValue,
        }),
      }))), [accountId, contactsQuery.data?.records]);

  const createMutation = useMutation({
    mutationFn: createChatAppBroadcast,
    onSuccess: () => {
      setCreateOpen(false);
      form.resetFields();
      setContactSearch('');
      createRequestId.current = requestId('broadcast');
      void queryClient.invalidateQueries({ queryKey: ['chatapp-broadcasts', accountId] });
      message.success('群发任务已创建');
    },
    onError: (error) => {
      message.error(errorMessage(error));
    },
  });
  const retryMutation = useMutation({
    mutationFn: (broadcast: ChatAppBroadcast) => {
      let clientRequestId = failureRetryRequestIds.current.get(broadcast.id);
      if (!clientRequestId) {
        clientRequestId = requestId('broadcast-retry');
        failureRetryRequestIds.current.set(broadcast.id, clientRequestId);
      }
      return retryChatAppBroadcastFailures(broadcast.id, {
        name: `${broadcast.name} - 失败重发`,
        clientRequestId,
      });
    },
    onSuccess: (_result, broadcast) => {
      failureRetryRequestIds.current.delete(broadcast.id);
      void queryClient.invalidateQueries({ queryKey: ['chatapp-broadcasts', accountId] });
      message.success('失败收件人已生成新的群发任务');
    },
    onError: (error) => {
      message.error(errorMessage(error));
    },
  });
  const reconciliationMutation = useMutation({
    mutationFn: (broadcastId: string) => requestChatAppBroadcastReconciliation(broadcastId),
    onSuccess: (_result, broadcastId) => {
      void queryClient.invalidateQueries({ queryKey: ['chatapp-broadcasts', accountId] });
      void queryClient.invalidateQueries({ queryKey: ['chatapp-broadcast-detail', broadcastId] });
      message.success('已创建重新对账任务');
    },
  });

  const changeAccount = (value: string) => {
    setAccountId(value);
    setPage(1);
    setFailureBroadcast(null);
    setDetailBroadcast(null);
    setFailurePage(1);
    setContactSearch('');
    form.resetFields();
    createRequestId.current = requestId('broadcast');
  };

  const submitCreate = (values: CreateFormValues) => {
    if (!accountId) return;
    const template = (templatesQuery.data ?? []).find(
      (item) => `${item.templateCode}::${item.languageCode}` === values.templateKey,
    );
    if (!template) return;
    const command: CreateChatAppBroadcastCommand = {
      channelAccountId: accountId,
      name: values.name.trim(),
      templateCode: template.templateCode,
      languageCode: template.languageCode,
      clientRequestId: createRequestId.current,
      recipients: values.identities.map((identity) => ({
        contactIdentityId: identity.value,
        templateParams: {},
      })),
      sharedTemplateParams: Object.fromEntries(Object.entries(
        values.sharedTemplateParams ?? {},
      ).filter(([, value]) => value != null && value.trim() !== '')),
    };
    createMutation.mutate(command);
  };

  const columns: TableColumnsType<ChatAppBroadcast> = [
    {
      title: '群发名称',
      dataIndex: 'name',
      key: 'name',
      render: (value: string, row) => (
        <Space direction="vertical" size={0}>
          <Text strong>{value}</Text>
          <Text type="secondary">{row.templateName} · {row.languageCode}</Text>
        </Space>
      ),
    },
    { title: '收件人', dataIndex: 'recipientCount', key: 'recipientCount', width: 90 },
    {
      title: '进度',
      key: 'progress',
      width: 170,
      render: (_, row) => `${row.successCount} 成功 / ${row.processingCount} 处理中 / ${row.failedCount} 失败`,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 130,
      render: (status: ChatAppBroadcastStatus) => (
        <Tag color={statusDisplay[status].color}>{statusDisplay[status].label}</Tag>
      ),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 180,
      render: formatTime,
    },
    {
      title: '详情',
      key: 'detail',
      width: 72,
      render: (_, row) => (
        <Button
          type="text"
          icon={<EyeOutlined />}
          aria-label={`查看群发 ${row.name}`}
          onClick={() => setDetailBroadcast(row)}
        />
      ),
    },
    {
      title: '失败明细',
      key: 'failures',
      width: 150,
      render: (_, row) => row.failedCount > 0 ? (
        <Button
          danger
          type="text"
          icon={<WarningOutlined />}
          aria-label={`查看 ${row.failedCount} 条失败`}
          onClick={() => {
            setFailureBroadcast(row);
            setFailurePage(1);
          }}
        >
          {row.failedCount} 条失败
        </Button>
      ) : <Text type="secondary">-</Text>,
    },
  ];

  const templateOptions = (templatesQuery.data ?? []).map((template: TemplateResponse) => ({
    value: `${template.templateCode}::${template.languageCode}`,
    label: template.displayName,
  }));
  const listError = broadcastsQuery.error ? errorMessage(broadcastsQuery.error) : null;

  return (
    <div style={{ width: '100%', minWidth: 0 }}>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <Space align="center" wrap style={{ justifyContent: 'space-between', width: '100%' }}>
          <Title level={4} style={{ margin: 0 }}>ChatApp 群发</Title>
          <Space wrap>
            <Select
              aria-label="ChatApp 账号"
              value={accountId}
              placeholder="选择 ChatApp 账号"
              style={{ width: 220, maxWidth: '100%' }}
              options={accounts.map((account) => ({
                value: account.channelAccountId,
                label: account.displayName,
              }))}
              onChange={changeAccount}
            />
            <Button
              type="primary"
              icon={<PlusOutlined />}
              aria-label="新建群发"
              disabled={!accountId}
              onClick={() => setCreateOpen(true)}
            >
              新建群发
            </Button>
            <Button
              aria-label="刷新群发记录"
              icon={<ReloadOutlined />}
              loading={broadcastsQuery.isFetching}
              onClick={() => void broadcastsQuery.refetch()}
            />
          </Space>
        </Space>
        {capabilitiesQuery.isSuccess && accounts.length === 0 ? (
          <Alert type="warning" showIcon message="没有可用的 ChatApp 账号" />
        ) : null}
        {listError ? (
          <Alert type="error" showIcon message={listError} action={(
            <Button size="small" onClick={() => void broadcastsQuery.refetch()}>重试</Button>
          )} />
        ) : null}
        <Table
          rowKey="id"
          columns={columns}
          dataSource={broadcastsQuery.data?.records ?? []}
          loading={capabilitiesQuery.isLoading || broadcastsQuery.isLoading}
          scroll={{ x: 980 }}
          pagination={{
            current: broadcastsQuery.data?.page ?? page,
            pageSize: 20,
            total: broadcastsQuery.data?.total ?? 0,
            showSizeChanger: false,
            onChange: setPage,
          }}
          locale={{ emptyText: '暂无群发记录' }}
        />
      </Space>

      <Drawer
        title="群发详情"
        open={Boolean(detailBroadcast)}
        width="min(720px, 100vw)"
        onClose={() => setDetailBroadcast(null)}
        extra={detailQuery.data?.broadcast.status === 'STATUS_UNKNOWN'
          && detailQuery.data.broadcast.providerGroupMessageId ? (
            <Button
              loading={reconciliationMutation.isPending}
              disabled={reconciliationMutation.isPending}
              onClick={() => reconciliationMutation.mutate(detailQuery.data!.broadcast.id)}
            >
              重新对账
            </Button>
          ) : null}
      >
        {detailQuery.error ? (
          <Alert type="error" showIcon message={errorMessage(detailQuery.error)} />
        ) : null}
        {reconciliationMutation.error ? (
          <Alert
            type="error"
            showIcon
            message={errorMessage(reconciliationMutation.error)}
            style={{ marginBottom: 16 }}
          />
        ) : null}
        {detailQuery.data ? (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Descriptions size="small" column={{ xs: 1, sm: 2 }} bordered>
              <Descriptions.Item label="状态">
                {statusDisplay[detailQuery.data.broadcast.status].label}
              </Descriptions.Item>
              <Descriptions.Item label="进度">
                {detailQuery.data.broadcast.successCount} 成功 / {detailQuery.data.broadcast.processingCount} 处理中 / {detailQuery.data.broadcast.failedCount} 失败
              </Descriptions.Item>
              <Descriptions.Item label="GroupMessageId">
                {detailQuery.data.broadcast.providerGroupMessageId ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="最近 RequestId">
                {detailQuery.data.broadcast.lastReconciliationRequestId
                  ?? detailQuery.data.broadcast.providerRequestId ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="Provider code">
                {detailQuery.data.broadcast.lastReconciliationProviderCode
                  ?? detailQuery.data.broadcast.providerCode ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="证据">
                {detailQuery.data.reconciliation.evidenceRows} 条 / 已匹配 {detailQuery.data.reconciliation.matchedRows} / 未匹配 {detailQuery.data.reconciliation.unmatchedRows}
              </Descriptions.Item>
              <Descriptions.Item label="诊断" span={2}>
                {detailQuery.data.reconciliation.latestDiagnosticCode ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="错误" span={2}>
                {[detailQuery.data.broadcast.errorCode, detailQuery.data.broadcast.errorMessage]
                  .filter(Boolean).join('：') || '-'}
              </Descriptions.Item>
            </Descriptions>
            <Table
              rowKey="id"
              size="small"
              dataSource={detailQuery.data.recipients}
              scroll={{ x: 920 }}
              pagination={false}
              columns={[
                { title: '联系人', dataIndex: 'recipientName', key: 'recipientName' },
                { title: '号码', dataIndex: 'maskedNumber', key: 'maskedNumber', width: 130 },
                { title: '消息 ID', dataIndex: 'messageId', key: 'messageId', width: 170,
                  render: (value: string | null) => value ?? '-' },
                { title: 'Provider ID', dataIndex: 'providerMessageId', key: 'providerMessageId', width: 170,
                  render: (value: string | null) => value ?? '-' },
                { title: '状态', dataIndex: 'status', key: 'status', width: 120 },
                { title: '失败原因', dataIndex: 'failureReason', key: 'failureReason',
                  onCell: () => ({ style: { whiteSpace: 'normal', wordBreak: 'break-word' } }) },
              ]}
            />
          </Space>
        ) : null}
      </Drawer>

      <Drawer
        title="新建群发"
        open={createOpen}
        width="min(560px, 100vw)"
        onClose={() => {
          setCreateOpen(false);
          form.resetFields();
          setContactSearch('');
          createRequestId.current = requestId('broadcast');
        }}
        extra={(
          <Button
            type="primary"
            loading={createMutation.isPending}
            onClick={() => form.submit()}
          >
            创建群发
          </Button>
        )}
      >
        {templatesQuery.error ? (
          <Alert
            type="error"
            showIcon
            message="模板加载失败"
            description={errorMessage(templatesQuery.error)}
            action={<Button size="small" onClick={() => void templatesQuery.refetch()}>重试模板</Button>}
            style={{ marginBottom: 16 }}
          />
        ) : null}
        {contactsQuery.error ? (
          <Alert
            type="error"
            showIcon
            message="联系人加载失败"
            description={errorMessage(contactsQuery.error)}
            action={<Button size="small" onClick={() => void contactsQuery.refetch()}>重试联系人</Button>}
            style={{ marginBottom: 16 }}
          />
        ) : null}
        <Form form={form} layout="vertical" onFinish={submitCreate} requiredMark={false}>
          <Form.Item
            name="name"
            label="群发名称"
            rules={[{ required: true, whitespace: true, max: 120, message: '请输入群发名称' }]}
          >
            <Input aria-label="群发名称" maxLength={120} />
          </Form.Item>
          <Form.Item name="templateKey" label="模板" rules={[{ required: true, message: '请选择模板' }]}>
            <Select
              aria-label="模板"
              loading={templatesQuery.isLoading}
              options={templateOptions}
              placeholder="选择已审核且允许发送的模板"
              onChange={() => form.setFieldValue('sharedTemplateParams', {})}
            />
          </Form.Item>
          <Form.Item
            name="identities"
            label="联系人"
            rules={[
              { required: true, message: '请选择联系人' },
              { validator: (_, value) => (value?.length ?? 0) <= 1000
                ? Promise.resolve()
                : Promise.reject(new Error('单批最多 1000 人')) },
            ]}
          >
            <Select
              aria-label="联系人"
              mode="multiple"
              labelInValue
              filterOption={false}
              options={contactOptions}
              loading={contactsQuery.isFetching}
              maxTagCount="responsive"
              placeholder="搜索并选择当前账号下的联系人"
              onSearch={setContactSearch}
            />
          </Form.Item>
          {selectedTemplate?.placeholders.map((placeholder) => (
            <Form.Item
              key={placeholder}
              name={['sharedTemplateParams', placeholder]}
              label={placeholder}
              rules={[{ required: true, whitespace: true, message: `请输入 ${placeholder}` }]}
            >
              <Input aria-label={placeholder} />
            </Form.Item>
          ))}
          {selectedTemplate ? (
            <Alert type="info" showIcon message="模板预览" description={selectedTemplate.body} />
          ) : null}
        </Form>
      </Drawer>

      <Drawer
        title="失败明细"
        open={Boolean(failureBroadcast)}
        width="min(560px, 100vw)"
        onClose={() => setFailureBroadcast(null)}
        extra={failureBroadcast && (failuresQuery.data?.total ?? 0) > 0 ? (
          <Button
            danger
            loading={retryMutation.isPending}
            onClick={() => retryMutation.mutate(failureBroadcast)}
          >
            仅重发失败联系人
          </Button>
        ) : null}
      >
        {failuresQuery.error ? (
          <Alert type="error" showIcon message={errorMessage(failuresQuery.error)} />
        ) : null}
        <Table
          rowKey="id"
          size="small"
          loading={failuresQuery.isLoading}
          dataSource={failuresQuery.data?.records ?? []}
          columns={[
            { title: '联系人', dataIndex: 'recipientName', key: 'recipientName' },
            { title: '号码', dataIndex: 'maskedNumber', key: 'maskedNumber', width: 130 },
            {
              title: '消息 ID',
              dataIndex: 'providerMessageId',
              key: 'providerMessageId',
              width: 170,
              render: (value: string | null) => value ?? '-',
            },
            {
              title: '状态',
              dataIndex: 'status',
              key: 'status',
              width: 100,
              render: () => <Tag color="error">发送失败</Tag>,
            },
            { title: '失败原因', dataIndex: 'failureReason', key: 'failureReason' },
            {
              title: '最后对账',
              dataIndex: 'lastReconciledAt',
              key: 'lastReconciledAt',
              width: 180,
              render: formatTime,
            },
          ]}
          scroll={{ x: 900 }}
          pagination={{
            current: failuresQuery.data?.page ?? failurePage,
            pageSize: 20,
            total: failuresQuery.data?.total ?? 0,
            showSizeChanger: false,
            onChange: setFailurePage,
          }}
          locale={{ emptyText: '暂无失败记录' }}
        />
      </Drawer>
    </div>
  );
}
