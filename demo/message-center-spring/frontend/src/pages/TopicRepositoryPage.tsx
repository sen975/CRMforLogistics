import { useState } from 'react';
import { Button, Collapse, Input, List, Pagination, Select, Space, Tag, Typography } from 'antd';
import { useTopicRepository } from '../hooks/useTopicRepository';
import type { TopicProjection } from '../api/types';
import { weComGroupDisplayName } from '../utils/weComGroupDisplayName';

const channelLabels: Record<string, string> = { chatapp: 'ChatApp', email: '邮件', phone: '电话', wecom: 'wecom' };

function ownerLabel(topic: TopicProjection): string {
  if (topic.ownerType === 'WECOM_GROUP') return weComGroupDisplayName(topic.ownerLabel);
  if (topic.contactRemark?.trim()) return `备注：${topic.contactRemark.trim()}`;
  if (topic.contactChannelNickname?.trim()) return `${channelLabels[topic.contactChannelType || ''] || topic.contactChannelType || '渠道'}：${topic.contactChannelNickname.trim()}`;
  if (topic.contactName?.trim()) return topic.contactName.trim();
  return '未命名联系人';
}

export default function TopicRepositoryPage() {
  const [search, setSearch] = useState(() => new URLSearchParams(window.location.search).get('search') ?? '');
  const [page, setPage] = useState(1);
  const [ownerType, setOwnerType] = useState<string>();
  const { data, isLoading, restore, requests, approve, reject } = useTopicRepository(search, page, 20, ownerType);
  return <div style={{ maxWidth: 960, margin: '0 auto', padding: 24 }}>
    <Typography.Title level={4}>Topic 仓库</Typography.Title>
    <Space style={{ marginBottom: 16 }}><Input.Search placeholder="搜索联系人或 Topic" defaultValue={search} allowClear onSearch={value => { setSearch(value); setPage(1); }} style={{ maxWidth: 420 }} /><Select allowClear placeholder="归属类型" value={ownerType} onChange={value => { setOwnerType(value); setPage(1); }} options={[{ value: 'CONTACT', label: '联系人' }, { value: 'WECOM_GROUP', label: '企业微信群' }]} /></Space>
    <List<TopicProjection> loading={isLoading} dataSource={(data?.records ?? []) as TopicProjection[]} renderItem={(topic: TopicProjection) => <List.Item actions={[<Button key="restore" type="link" loading={restore.isPending} onClick={() => restore.mutate({ topicId: topic.id, contactId: topic.contactId || undefined })}>恢复</Button>] }>
      <List.Item.Meta title={<Space direction="vertical" size={2}><Typography.Text strong>{topic.title}</Typography.Text><Typography.Text type="secondary">{topic.ownerType === 'WECOM_GROUP' ? '归属群：' : '归属联系人：'}{ownerLabel(topic)}{topic.contactId ? ` · ${topic.contactId}` : ''}</Typography.Text></Space>} description={<Space direction="vertical"><Typography.Text>{topic.summary}</Typography.Text><Typography.Text type="secondary">事件时间：{new Date(topic.firstOccurredAt).toLocaleString('zh-CN')} - {new Date(topic.lastOccurredAt).toLocaleString('zh-CN')} · {topic.sourceCount} 条来源</Typography.Text><Collapse ghost items={[{ key: 'sources', label: `来源（${topic.sourceCount}）`, children: <Space wrap>{topic.sourceItems.map(source => <Typography.Text key={source.id}>{channelLabels[source.channelType] || source.channelType} · {new Date(source.occurredAt).toLocaleString('zh-CN')}</Typography.Text>)}</Space> }]} /></Space>} />
    </List.Item>} />
    {!!requests?.data?.length && <><Typography.Title level={5}>待审批群 Topic</Typography.Title><List dataSource={requests.data} renderItem={request => <List.Item actions={[<Button key="approve" type="link" onClick={() => approve.mutate({ requestId: request.id })}>批准入库</Button>, <Button key="reject" type="link" danger onClick={() => reject.mutate({ requestId: request.id })}>拒绝</Button>]}><List.Item.Meta title={request.topicTitle} description={<Space><Tag color="purple">{weComGroupDisplayName(request.ownerLabel)}</Tag><Typography.Text type="secondary">申请时间：{new Date(request.createdAt).toLocaleString('zh-CN')}</Typography.Text></Space>} /></List.Item>} /></>}
    <Pagination current={page} pageSize={20} total={data?.total ?? 0} onChange={setPage} showSizeChanger={false} style={{ marginTop: 16 }} />
  </div>;
}
