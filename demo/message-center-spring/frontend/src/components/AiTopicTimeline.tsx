import { useMemo, useState } from 'react';
import { Alert, Button, Checkbox, Collapse, Empty, Input, Popconfirm, Space, Spin, Tag, Typography } from 'antd';
import { MergeCellsOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import type { ContactTopicsResponse, TopicProjection, TopicSourceItem } from '../api/types';
import type { useTopicTimeline } from '../hooks/useTopicTimeline';

const { Text, Title } = Typography;
const labels: Record<string, string> = { chatapp: 'ChatApp', email: '邮件', phone: '电话' };

interface Props {
  contactId: string;
  timeline?: ContactTopicsResponse;
  onSourceClick: (source: TopicSourceItem) => void;
  actions?: Pick<ReturnType<typeof useTopicTimeline>, 'update' | 'merge' | 'retry'> & { discard?: ReturnType<typeof useTopicTimeline>['discard'] };
}

export default function AiTopicTimeline({ contactId, timeline, onSourceClick, actions }: Props) {
  const [editing, setEditing] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [draft, setDraft] = useState({ title: '', summary: '', version: 0 });
  const [timelineOpen, setTimelineOpen] = useState(true);
  const topics = useMemo(() => [...(timeline?.topics ?? [])].filter(t => !(t.channels as string[]).includes('wecom')).sort((a, b) => Date.parse(b.lastOccurredAt) - Date.parse(a.lastOccurredAt)), [timeline]);
  if (!timeline || timeline.generation.status === 'GENERATING') return <div style={{ padding: 16 }}><Title level={5}>Topic 时间轴</Title><Space><Spin size="small" />正在整理历史沟通</Space></div>;
  if (timeline.weComUnsupported) return <div style={{ padding: 16 }}><Title level={5}>Topic 时间轴</Title><Alert type="info" showIcon message="当前渠道暂不支持 AI Topic 总结" /></div>;
  if (timeline.generation.status === 'FAILED') return <div style={{ padding: 16 }}><Title level={5}>Topic 时间轴</Title><Alert type="error" showIcon message="Topic 总结失败" description={timeline.generation.errorCode || 'AI 服务暂不可用'} action={<Button size="small" icon={<ReloadOutlined />} onClick={() => actions?.retry.mutate()}>重试</Button>} /></div>;
  return <div style={{ padding: 16 }} data-testid="ai-topic-timeline">
    <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 8 }}><Button type="text" onClick={() => setTimelineOpen(v => !v)} aria-expanded={timelineOpen}><Title level={5} style={{ margin: 0 }}>Topic 时间轴</Title></Button><Popconfirm title="确认合并所选 Topic？" description="合并后将保留最早 Topic，其他 Topic 会归档。" okText="确认合并" cancelText="取消" onConfirm={() => actions?.merge.mutate({ contactId, topicIds: selected, expectedVersions: Object.fromEntries(selected.map(id => [id, topics.find(t => t.id === id)?.version ?? 0])) })}><Button size="small" icon={<MergeCellsOutlined />} disabled={selected.length < 2}>合并 Topic</Button></Popconfirm></Space>
    {timelineOpen && (topics.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无 Topic" /> : <Collapse bordered={false} items={topics.map((topic: TopicProjection) => ({ key: topic.id, label: <Space><Checkbox checked={selected.includes(topic.id)} onClick={e => e.stopPropagation()} onChange={e => setSelected(v => e.target.checked ? [...v, topic.id] : v.filter(id => id !== topic.id))} /><Text strong>{topic.title}</Text></Space>, children: <div>
        <Space align="start">
          {editing === topic.id ? <Input value={draft.title} onChange={e => setDraft(v => ({ ...v, title: e.target.value }))} style={{ width: 180 }} /> : <Text strong>{topic.title}</Text>}
          {topic.channels.map(ch => <Tag key={ch}>{labels[ch] || ch}</Tag>)}
        </Space>
        {editing === topic.id ? <Space style={{ width: '100%', marginTop: 8 }}><Input.TextArea value={draft.summary} onChange={e => setDraft(v => ({ ...v, summary: e.target.value }))} autoSize={{ minRows: 2, maxRows: 5 }} /><Button icon={<SaveOutlined />} onClick={() => { actions?.update.mutate({ topicId: topic.id, data: { contactId, title: draft.title, confirmedSummary: draft.summary, expectedVersion: draft.version } }); setEditing(null); }} /></Space> : <><Text type="secondary" style={{ display: 'block', marginTop: 6 }}>{topic.summary}</Text><Text type="secondary" style={{ fontSize: 12 }}>{new Date(topic.firstOccurredAt).toLocaleDateString('zh-CN')} - {new Date(topic.lastOccurredAt).toLocaleDateString('zh-CN')} · {topic.sourceCount} 条来源</Text></>}
        {editing !== topic.id && <Button type="link" size="small" onClick={() => { setEditing(topic.id); setDraft({ title: topic.title, summary: topic.summary, version: topic.version }); }}>编辑</Button>}
        <Space wrap><Popconfirm title="确认弃用此 Topic？" okText="确认" cancelText="取消" onConfirm={() => actions?.discard?.mutate({ topicId: topic.id })}><Button danger type="link" size="small">弃用</Button></Popconfirm>{topic.sourceItems.filter(s => (s.channelType as string) !== 'wecom').map(source => <Button key={source.id} type="link" size="small" onClick={() => onSourceClick(source)}>{labels[source.channelType]} {new Date(source.occurredAt).toLocaleDateString('zh-CN')}</Button>)}</Space>
      </div>}))} />)}
  </div>;
}
