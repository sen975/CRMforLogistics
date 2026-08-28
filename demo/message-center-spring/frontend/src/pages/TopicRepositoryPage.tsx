import { useState } from 'react';
import { Button, Collapse, Input, List, Pagination, Space, Typography } from 'antd';
import { useTopicRepository } from '../hooks/useTopicRepository';
import type { TopicProjection } from '../api/types';

export default function TopicRepositoryPage() {
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const { data, isLoading, restore } = useTopicRepository(search, page);
  return <div style={{ maxWidth: 960, margin: '0 auto', padding: 24 }}>
    <Typography.Title level={4}>Topic 仓库</Typography.Title>
    <Input.Search placeholder="搜索联系人或 Topic" allowClear onSearch={value => { setSearch(value); setPage(1); }} style={{ maxWidth: 420, marginBottom: 16 }} />
    <List<TopicProjection> loading={isLoading} dataSource={(data?.records ?? []) as TopicProjection[]} renderItem={(topic: TopicProjection) => <List.Item actions={[<Button key="restore" type="link" loading={restore.isPending} onClick={() => topic.contactId && restore.mutate({ topicId: topic.id, contactId: topic.contactId })}>恢复</Button>]}>
      <List.Item.Meta title={topic.title} description={<Space direction="vertical"><Typography.Text>{topic.summary}</Typography.Text><Typography.Text type="secondary">{new Date(topic.firstOccurredAt).toLocaleString('zh-CN')} - {new Date(topic.lastOccurredAt).toLocaleString('zh-CN')} · {topic.sourceCount} 条来源</Typography.Text><Collapse ghost items={[{ key: 'sources', label: '来源消息', children: <Space wrap>{topic.sourceItems.map(source => <Typography.Text key={source.id}>{source.channelType} · {new Date(source.occurredAt).toLocaleDateString('zh-CN')}</Typography.Text>)}</Space> }]} /></Space>} />
    </List.Item>} />
    <Pagination current={page} pageSize={20} total={data?.total ?? 0} onChange={setPage} showSizeChanger={false} style={{ marginTop: 16 }} />
  </div>;
}
