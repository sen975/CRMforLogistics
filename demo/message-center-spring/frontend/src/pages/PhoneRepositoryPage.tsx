import { useState } from 'react';
import { Input, Table, Tag, Typography, Space, Button, Modal, App } from 'antd';
import { SearchOutlined, PhoneOutlined, PlayCircleOutlined, UploadOutlined, FileSearchOutlined, ReloadOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchPhoneRepository, retryCallRecord } from '../api/endpoints';
import type { PhoneRecordResponse } from '../api/types';
import { useNavigate } from 'react-router-dom';
import CallRecordUploadForm from '../components/CallRecordUploadForm';
import { useDetailPanel } from '../hooks/useDetailPanel';

const { Text } = Typography;

function renderPhoneValue(value: string) {
  const phoneValue = value || '—';
  return (
    <Text ellipsis={{ tooltip: phoneValue }} style={{ display: 'block', minWidth: 0, whiteSpace: 'nowrap' }}>
      {phoneValue}
    </Text>
  );
}

export default function PhoneRepositoryPage() {
  const [query, setQuery] = useState('');
  const [uploadOpen, setUploadOpen] = useState(false);
  const [retrying, setRetrying] = useState<string | null>(null);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const { selectCallRecord } = useDetailPanel();

  const openTimeline = (record: PhoneRecordResponse) => {
    if (!record.contactId) {
      message.warning('该电话记录尚未关联联系人，暂时无法打开时间轴');
      return;
    }
    navigate(`/conversations/contact/${encodeURIComponent(record.contactId)}?channel=phone`);
  };

  const { data, isLoading } = useQuery({
    queryKey: ['phoneRepository', query],
    queryFn: () => fetchPhoneRepository({ query: query || undefined, limit: 50 }),
    refetchInterval: 10000,
  });

  const handleRetry = async (r: PhoneRecordResponse) => {
    setRetrying(r.id);
    try {
      await retryCallRecord(r.id, r.clientRequestId);
      message.success('已重新加入转录队列');
      queryClient.invalidateQueries({ queryKey: ['phoneRepository'] });
      queryClient.invalidateQueries({ queryKey: ['callRecord', r.id] });
    } catch {
      message.error('重试失败');
    } finally {
      setRetrying(null);
    }
  };

  const directionTag = (d: string) => {
    const color = d === 'inbound' ? 'blue' : 'green';
    const label = d === 'inbound' ? '呼入' : '呼出';
    return <Tag color={color}>{label}</Tag>;
  };

  const stateTag = (state: string) => {
    const map: Record<string, { color: string; label: string }> = {
      queued: { color: 'default', label: '排队中' },
      processing: { color: 'processing', label: '转录中' },
      completed: { color: 'success', label: '已完成' },
      failed: { color: 'error', label: '失败' },
    };
    const s = map[state] ?? { color: 'default', label: state };
    return <Tag color={s.color}>{s.label}</Tag>;
  };

  const formatSeconds = (s: number) => {
    const m = Math.floor(s / 60);
    const sec = Math.floor(s % 60);
    return `${m}:${sec.toString().padStart(2, '0')}`;
  };

  const columns = [
    {
      title: '联系人',
      dataIndex: 'contactDisplayName',
      key: 'contact',
      render: (name: string, r: PhoneRecordResponse) => (
        <Space style={{ minWidth: 0, maxWidth: '100%' }}>
          <PhoneOutlined />
          {renderPhoneValue(name || '—')}
        </Space>
      ),
    },
    {
      title: '号码',
      dataIndex: 'phonePointId',
      key: 'phone',
      width: 160,
      render: (phoneValue: string) => renderPhoneValue(phoneValue.replace(/^phone:/, '')),
    },
    {
      title: '方向',
      dataIndex: 'direction',
      key: 'direction',
      width: 72,
      render: (d: string) => directionTag(d),
    },
    {
      title: '时间',
      dataIndex: 'occurredAt',
      key: 'time',
      width: 180,
      render: (t: string) => new Date(t).toLocaleString('zh-CN'),
    },
    {
      title: '时长',
      dataIndex: 'durationSeconds',
      key: 'duration',
      width: 80,
      render: (s: number) => formatSeconds(s),
    },
    {
      title: '状态',
      dataIndex: 'transcriptionState',
      key: 'state',
      width: 200,
      render: (s: string, r: PhoneRecordResponse) => (
        <Space size={4}>
          {stateTag(s)}
          {r.errorMessage && (s === 'failed' || s === 'queued') && (
            <Tag color="red" title={r.errorMessage}>
              {r.errorMessage.length > 16 ? r.errorMessage.slice(0, 16) + '…' : r.errorMessage}
            </Tag>
          )}
          {s === 'queued' && r.transcriptionAttempts > 0 && (
            <Tag>重试{r.transcriptionAttempts}</Tag>
          )}
        </Space>
      ),
    },
    {
      title: '备注',
      dataIndex: 'note',
      key: 'note',
      ellipsis: true,
      width: 160,
    },
    {
      title: '操作',
      key: 'actions',
      width: 120,
      render: (_: unknown, r: PhoneRecordResponse) => (
        <Space size={8}>
          <FileSearchOutlined
            style={{ cursor: 'pointer', fontSize: 16 }}
            title="查看详情"
            onClick={(e) => { e.stopPropagation(); selectCallRecord(r.id); }}
          />
          <PlayCircleOutlined
            style={{ cursor: 'pointer', fontSize: 16 }}
            title="查看会话"
            onClick={(e) => { e.stopPropagation(); openTimeline(r); }}
          />
          {r.transcriptionState === 'failed' && (
            <ReloadOutlined
              style={{ cursor: 'pointer', fontSize: 16, color: '#ff4d4f' }}
              title="重新转录"
              spin={retrying === r.id}
              onClick={(e) => { e.stopPropagation(); handleRetry(r); }}
            />
          )}
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          电话仓库
        </Typography.Title>
        <Button type="primary" icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>
          上传录音
        </Button>
      </div>
      <Input.Search
        placeholder="搜索号码、备注..."
        allowClear
        onSearch={(v) => setQuery(v)}
        style={{ maxWidth: 400, marginBottom: 16 }}
        prefix={<SearchOutlined />}
      />
      <Table
        columns={columns}
        dataSource={data?.items ?? []}
        rowKey="id"
        loading={isLoading}
        pagination={false}
        size="small"
        scroll={{ x: 1100 }}
        onRow={(r) => ({
          onClick: () => openTimeline(r),
          style: { cursor: r.contactId ? 'pointer' : 'default' },
        })}
      />
      {data && (
        <div style={{ marginTop: 12, color: '#999', fontSize: 13 }}>
          共 {data.totalCount} 条记录
        </div>
      )}

      <Modal
        title="上传电话录音"
        open={uploadOpen}
        onCancel={() => setUploadOpen(false)}
        footer={null}
        destroyOnClose
        width={520}
      >
        <CallRecordUploadForm
          onSuccess={() => {
            setUploadOpen(false);
            queryClient.invalidateQueries({ queryKey: ['phoneRepository'] });
          }}
        />
      </Modal>
    </div>
  );
}
