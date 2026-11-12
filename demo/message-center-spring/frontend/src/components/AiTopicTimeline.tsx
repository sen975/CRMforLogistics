import { useMemo, useState } from 'react';
import { Alert, Button, Checkbox, Collapse, Empty, Input, Modal, Popconfirm, Space, Spin, Tag, Typography } from 'antd';
import { MergeCellsOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import type { ContactTopicsResponse, TopicFusionPreviewResponse, TopicProjection, TopicSourceItem, WeComGroupTopicsResponse } from '../api/types';
import type { useTopicTimeline } from '../hooks/useTopicTimeline';
import { weComGroupDisplayName } from '../utils/weComGroupDisplayName';

const { Text } = Typography;
const labels: Record<string, string> = { chatapp: 'ChatApp', email: '邮件', phone: '电话', wecom: 'wecom' };
const reviewOriginLabels: Record<NonNullable<TopicProjection['reviewOrigin']>, string> = {
  MERGE_SOURCE: '联系人合并',
  SPLIT_SOURCE: '联系人拆分',
  MANUAL_SELECTION: '人工整理',
};

function formatEventTime(value: string, withTime = false): string {
  return new Date(value).toLocaleString('zh-CN', withTime
    ? { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }
    : { year: 'numeric', month: '2-digit', day: '2-digit' });
}

interface Props {
  contactId?: string;
  timeline?: ContactTopicsResponse | WeComGroupTopicsResponse;
  onSourceClick?: (source: TopicSourceItem) => void;
  groupMode?: boolean;
  actions?: Partial<Pick<ReturnType<typeof useTopicTimeline>, 'update' | 'merge' | 'retry' | 'store'>>;
  pendingTopics?: TopicProjection[];
  onKeepPending?: (topicId: string) => void;
  onPreviewFusion?: (topicIds: string[], expectedVersions: Record<string, number>) => Promise<TopicFusionPreviewResponse>;
  onApplyFusion?: (previewId: string) => Promise<void>;
}

export default function AiTopicTimeline({ contactId, timeline, onSourceClick, groupMode = false, actions, pendingTopics = [], onKeepPending, onPreviewFusion, onApplyFusion }: Props) {
  const [editing, setEditing] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [draft, setDraft] = useState({ title: '', summary: '', version: 0 });
  const [timelineOpen, setTimelineOpen] = useState(true);
  const [fusionPreview, setFusionPreview] = useState<TopicFusionPreviewResponse | null>(null);
  const [fusionLoading, setFusionLoading] = useState(false);
  const [fusionError, setFusionError] = useState<string | null>(null);
  const topics = useMemo(() => [...(timeline?.topics ?? [])]
    .sort((a, b) => Date.parse(b.lastOccurredAt) - Date.parse(a.lastOccurredAt)), [timeline]);
  const title = groupMode ? '群 Topic 时间轴' : 'Topic 时间轴';
  const selectableTopics = useMemo(() => [...topics.filter(topic => !topic.isReferencedGroupTopic), ...pendingTopics], [topics, pendingTopics]);
  const previewFusion = async () => {
    if (!onPreviewFusion || selected.length < 2) return;
    setFusionLoading(true); setFusionError(null);
    try {
      const versions = Object.fromEntries(selected.map(id => [id, selectableTopics.find(topic => topic.id === id)?.version ?? 0]));
      setFusionPreview(await onPreviewFusion(selected, versions));
    } catch {
      setFusionError('融合预览失败，请刷新后重试');
    } finally { setFusionLoading(false); }
  };
  const applyFusion = async () => {
    if (!fusionPreview || !onApplyFusion) return;
    setFusionLoading(true); setFusionError(null);
    try {
      await onApplyFusion(fusionPreview.previewId);
      setFusionPreview(null); setSelected([]);
    } catch {
      setFusionError('融合预览已过期或 Topic 已变化，请重新预览');
    } finally { setFusionLoading(false); }
  };
  if (!timeline || timeline.generation.status === 'GENERATING') return <div className="cd-body" style={{ padding: 16 }}><span className="cd-group-title">{title}</span><Space><Spin size="small" />正在整理历史沟通</Space></div>;
  if (timeline.weComUnsupported) return <div className="cd-body" style={{ padding: 16 }}><span className="cd-group-title">Topic 时间轴</span><Alert type="info" showIcon message="当前渠道暂不支持 AI Topic 总结" /></div>;
  if (timeline.generation.status === 'FAILED') return <div className="cd-body" style={{ padding: 16 }}><span className="cd-group-title">{title}</span><Alert type="error" showIcon message="Topic 总结失败" description={timeline.generation.errorCode || 'AI 服务暂不可用'} action={actions?.retry ? <Button size="small" icon={<ReloadOutlined aria-hidden="true" />} loading={actions.retry.isPending} disabled={actions.retry.isPending} onClick={() => actions.retry?.mutate()}>重试</Button> : undefined} /></div>;
  return <div className="cd-body" style={{ padding: groupMode ? '16px 0' : 16 }} data-testid="ai-topic-timeline">
    <div className="cd-toolbar" data-testid="ai-topic-timeline-toolbar" style={{ width: '100%', justifyContent: 'space-between', marginBottom: 8, padding: groupMode ? '0 16px' : 0 }}><Button type="text" className="cd-collapse" onClick={() => setTimelineOpen(v => !v)} aria-expanded={timelineOpen}><span className="cd-group-title">{title}</span></Button>{!groupMode && <Button size="small" icon={<MergeCellsOutlined aria-hidden="true" />} loading={fusionLoading} disabled={selected.length < 2 || !onPreviewFusion} onClick={previewFusion}>合并 Topic</Button>}</div>
    {fusionError && <Alert type="error" showIcon message={fusionError} style={{ marginBottom: 8 }} />}
    {timelineOpen && (topics.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无 Topic" /> : <Collapse bordered={false} className="cd-topics" items={topics.map((topic: TopicProjection) => ({ key: topic.id, label: <div className="cd-topic-label">{!groupMode && !topic.isReferencedGroupTopic && <Checkbox checked={selected.includes(topic.id)} onClick={e => e.stopPropagation()} onChange={e => setSelected(v => e.target.checked ? [...v, topic.id] : v.filter(id => id !== topic.id))} />}<div className="cd-topic-main"><div className="cd-topic-line"><Text strong className="cd-topic-title">{topic.title}</Text>{topic.isReferencedGroupTopic && !groupMode && <Tag color="purple">{weComGroupDisplayName(topic.ownerLabel)}</Tag>}</div><span className="cd-topic-time">最近事件时间 {formatEventTime(topic.lastOccurredAt, true)}</span></div></div>, children: <div>
        <div id={`topic-${topic.id}`}>
        <Space align="start">
          {editing === topic.id && <Input value={draft.title} onChange={e => setDraft(v => ({ ...v, title: e.target.value }))} style={{ width: 180 }} />}
          {groupMode ? <Tag color="purple">群 Topic</Tag> : topic.isReferencedGroupTopic && <Tag color="purple">只读群 Topic</Tag>}
          {topic.channels.map(ch => <Tag key={ch}>{labels[ch] || ch}</Tag>)}
        </Space>
        {editing === topic.id ? <Space style={{ width: '100%', marginTop: 8 }}><Input.TextArea value={draft.summary} onChange={e => setDraft(v => ({ ...v, summary: e.target.value }))} autoSize={{ minRows: 2, maxRows: 5 }} /><Button icon={<SaveOutlined />} onClick={() => { actions?.update?.mutate({ topicId: topic.id, data: { contactId: contactId!, title: draft.title, confirmedSummary: draft.summary, expectedVersion: draft.version } }); setEditing(null); }} /></Space> : <><Text type="secondary" className="cd-topic-summary">{topic.summary}</Text><div className="cd-callout"><Text strong className="cd-callout-title">事件时间</Text><Text type="secondary" style={{ display: 'block' }}>首个事件 {formatEventTime(topic.firstOccurredAt, true)}</Text><Text type="secondary" style={{ display: 'block' }}>最近事件 {formatEventTime(topic.lastOccurredAt, true)}</Text></div></>}
        {!groupMode && !topic.isReferencedGroupTopic && editing !== topic.id && <Button type="link" size="small" onClick={() => { setEditing(topic.id); setDraft({ title: topic.title, summary: topic.summary, version: topic.version }); }}>编辑</Button>}
        {!groupMode && !topic.isReferencedGroupTopic && <Popconfirm title="确认将此 Topic 入库？" okText="确认" cancelText="取消" onConfirm={() => actions?.store?.mutate({ topicId: topic.id })}><Button type="link" size="small">入库</Button></Popconfirm>}
        {(groupMode || topic.isReferencedGroupTopic) && <Button type="link" size="small" onClick={() => actions?.store?.mutate({ topicId: topic.id })}>申请入库</Button>}
        <Collapse ghost className="cd-sources" items={[{ key: 'sources', label: `来源（${topic.sourceCount}）`, children: <Space wrap className="cd-srcs">{topic.sourceItems.map(source => onSourceClick ? <Button key={source.id} className="cd-src" type="link" size="small" onClick={() => onSourceClick(source)}>{labels[source.channelType] || source.channelType} · {formatEventTime(source.occurredAt, true)}</Button> : <Text key={source.id} type="secondary">{labels[source.channelType] || source.channelType} · {formatEventTime(source.occurredAt, true)}</Text>)}</Space> }]} />
        </div>
      </div>}))} />)}
    {!groupMode && <div className="cd-group cd-pending-group" data-testid="topic-review-pending" style={{ marginTop: 16 }}>
      <h3 className="cd-group-title"><span>待确定 Topic</span>{pendingTopics.length > 0 && <span className="cd-cnt is-warn">{pendingTopics.length}</span>}</h3>
      {pendingTopics.length === 0 ? <Text type="secondary" className="cd-empty">暂无待确定 Topic</Text> : <Collapse bordered={false} className="cd-topics cd-pending" items={pendingTopics.map(topic => ({
        key: topic.id,
        label: <Space><Checkbox checked={selected.includes(topic.id)} onClick={event => event.stopPropagation()} onChange={event => setSelected(current => event.target.checked ? [...current, topic.id] : current.filter(id => id !== topic.id))} /><Tag color="orange">{topic.reviewOrigin ? reviewOriginLabels[topic.reviewOrigin] : '待确定'}</Tag><Text strong>{topic.title}</Text><Text type="secondary">{topic.sourceCount} 条来源</Text></Space>,
        children: <Space direction="vertical" style={{ width: '100%' }}><Text type="secondary">{topic.summary}</Text>{topic.reviewSourceTopicTitle && <Text type="secondary">原 Topic：{topic.reviewSourceTopicTitle}</Text>}<Text type="secondary">时间范围 {formatEventTime(topic.firstOccurredAt, true)} 至 {formatEventTime(topic.lastOccurredAt, true)}</Text><Space><Button size="small" onClick={() => onKeepPending?.(topic.id)}>保留</Button><Button size="small" onClick={() => actions?.store?.mutate({ topicId: topic.id })}>入库</Button></Space></Space>,
      }))} />}
    </div>}
    <Modal title="Topic 融合预览" open={fusionPreview !== null} okText="确认融合" cancelText="取消"
      confirmLoading={fusionLoading} onOk={applyFusion} onCancel={() => setFusionPreview(null)}>
      {fusionPreview && <Space direction="vertical" style={{ width: '100%' }}>
        <Text strong>{fusionPreview.title}</Text>
        <Text>{fusionPreview.summary}</Text>
        <Text type="secondary">融合 {fusionPreview.topicIds.length} 个 Topic，共 {fusionPreview.sourceCount} 条来源；确认后会生成新的 Topic。</Text>
      </Space>}
    </Modal>
  </div>;
}
