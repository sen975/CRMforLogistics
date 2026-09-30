import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Checkbox, Empty, Input, Select, Space, Spin, Tag, Typography } from 'antd';
import { CheckOutlined, RobotOutlined, SearchOutlined } from '@ant-design/icons';
import { applyManualReview, fetchManualReviewSources, previewManualReview } from '../api/endpoints';
import type {
  ContactIdentityResponse,
  ManualReviewAssignment,
  ManualReviewPreviewResponse,
  ManualReviewSourceListResponse,
  ManualReviewSourceOption,
  TopicProjection,
} from '../api/types';

const { Text } = Typography;
const channelLabels: Record<string, string> = { chatapp: 'ChatApp', email: '邮件', phone: '电话', wecom: '企业微信', whatsapp: 'WhatsApp' };

interface Props {
  contactId: string;
  identities: ContactIdentityResponse[];
  topics: TopicProjection[];
  onApplied: () => void;
}

function toIso(value: string): string | undefined {
  return value ? new Date(value).toISOString() : undefined;
}

function errorText(error: unknown): string {
  const code = (error as { response?: { data?: { code?: string } } })?.response?.data?.code;
  if (code === 'TOPIC_REVIEW_EXPIRED' || code === 'TOPIC_REVIEW_SNAPSHOT_CONFLICT' || code === 'TOPIC_VERSION_CONFLICT') {
    return '预览已过期或数据已变化，请重新查询并生成预览';
  }
  return 'Topic 整理失败，请稍后重试';
}

function unavailableReason(source: ManualReviewSourceOption): string {
  if (source.channelType.toLowerCase() === 'wecom' && source.excludedReason === 'WECOM_SUMMARY_NOT_COMPLETED') {
    return '企业微信摘要未完成，暂不可选';
  }
  if (source.excludedReason === 'TOPIC_REVIEW_SOURCE_LOCKED') {
    return '该记录已被其他 Topic 占用，暂不可选';
  }
  return '该记录当前不可选';
}

export default function AiTopicManualReviewPanel({ contactId, identities, topics, onApplied }: Props) {
  const supportedIdentities = useMemo(() => identities.filter(identity =>
    ['chatapp', 'email', 'phone', 'wecom', 'whatsapp'].includes(identity.channelType.toLowerCase())), [identities]);
  const [open, setOpen] = useState(false);
  const [identityId, setIdentityId] = useState(supportedIdentities[0]?.id ?? '');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [sources, setSources] = useState<ManualReviewSourceListResponse | null>(null);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [preview, setPreview] = useState<ManualReviewPreviewResponse | null>(null);
  const [assignments, setAssignments] = useState<ManualReviewAssignment[]>([]);
  const [loading, setLoading] = useState<'sources' | 'preview' | 'apply' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expandedTopicId, setExpandedTopicId] = useState<string | null>(null);

  useEffect(() => {
    setIdentityId(supportedIdentities[0]?.id ?? '');
    setSources(null);
    setSelectedIds([]);
    setPreview(null);
    setAssignments([]);
    setError(null);
    setExpandedTopicId(null);
  }, [contactId, supportedIdentities]);

  const selectableIds = useMemo(() => sources?.items.filter(item => item.selectable).map(item => item.id) ?? [], [sources]);
  const allSelected = selectableIds.length > 0 && selectableIds.every(id => selectedIds.includes(id));
  const selectedIdentity = supportedIdentities.find(identity => identity.id === identityId);
  const isWeComSelection = selectedIdentity?.channelType.toLowerCase() === 'wecom';

  const loadSources = async () => {
    if (!identityId) return;
    setLoading('sources'); setError(null); setPreview(null); setAssignments([]); setSelectedIds([]); setExpandedTopicId(null);
    try {
      setSources(await fetchManualReviewSources(contactId, {
        contactIdentityId: identityId, from: toIso(from), to: toIso(to),
      }));
    } catch (failure) { setError(errorText(failure)); }
    finally { setLoading(null); }
  };

  const createPreview = async () => {
    if (!identityId || selectedIds.length === 0) return;
    setLoading('preview'); setError(null);
    try {
      const result = await previewManualReview(contactId, {
        sourceIds: selectedIds, contactIdentityId: identityId, from: toIso(from), to: toIso(to),
      });
      setPreview(result); setAssignments(result.assignments);
    } catch (failure) { setError(errorText(failure)); }
    finally { setLoading(null); }
  };

  const applyPreview = async () => {
    if (!preview) return;
    setLoading('apply'); setError(null);
    try {
      await applyManualReview(contactId, preview.previewId, preview.sourceFingerprint, assignments);
      setSources(null); setSelectedIds([]); setPreview(null); setAssignments([]); setExpandedTopicId(null); onApplied();
    } catch (failure) { setError(errorText(failure)); }
    finally { setLoading(null); }
  };

  return <div className="cd-group cd-review" data-testid="topic-manual-review" style={{ margin: '0 16px 16px' }}>
    <Button aria-label="手动整理 Topic" icon={<RobotOutlined aria-hidden="true" />} onClick={() => setOpen(value => !value)} aria-expanded={open}>手动整理 Topic</Button>
    {open && <div className="cd-card" style={{ marginTop: 12 }}>
      <Space direction="vertical" size={10} style={{ width: '100%' }}>
        <Select aria-label="联系方式" value={identityId || undefined} onChange={setIdentityId} style={{ width: '100%' }}
          placeholder="选择联系方式" options={supportedIdentities.map(identity => ({
            value: identity.id,
            label: `${channelLabels[identity.channelType] ?? identity.channelType} · ${identity.displayName || identity.identityValue}`,
          }))} />
        <Space wrap>
          <Input aria-label="开始时间" type="datetime-local" value={from} onChange={event => setFrom(event.target.value)} />
          <Input aria-label="结束时间" type="datetime-local" value={to} onChange={event => setTo(event.target.value)} />
          <Button aria-label="查询记录" icon={<SearchOutlined aria-hidden="true" />} disabled={!identityId} loading={loading === 'sources'} onClick={loadSources}>查询记录</Button>
        </Space>
        {error && <Alert type="error" showIcon message={error} />}
        {isWeComSelection && sources?.wecomExcludedReason === 'WECOM_SUMMARY_NOT_COMPLETED' &&
          <Alert type="info" showIcon message="企业微信摘要尚未完成，暂不能用于 Topic 整理" />}
        {sources?.hasMore && <Alert type="warning" showIcon message="当前结果超过 200 条，请缩小时间范围" />}
        {loading === 'sources' ? <Spin size="small" /> : sources && <>
          <Checkbox aria-label="全选当前结果" checked={allSelected} indeterminate={selectedIds.length > 0 && !allSelected}
            onChange={event => setSelectedIds(event.target.checked ? selectableIds : [])}>全选当前结果</Checkbox>
          {sources.items.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前范围没有可整理记录" /> :
            <Space direction="vertical" size={6} className="cd-src-list" style={{ width: '100%' }}>
              {sources.items.map(source => <div key={source.id} className="cd-src-row">
                <Checkbox aria-label={`选择记录 ${source.id}`} disabled={!source.selectable} checked={selectedIds.includes(source.id)}
                  onChange={event => setSelectedIds(current => event.target.checked ? [...current, source.id] : current.filter(id => id !== source.id))} />
                <div style={{ minWidth: 0 }}><Space wrap><Tag>{channelLabels[source.channelType] ?? source.channelType}</Tag><Text type="secondary">{new Date(source.occurredAt).toLocaleString('zh-CN')}</Text></Space>
                  <Text strong style={{ display: 'block' }}>{source.subject || source.text.slice(0, 40) || '无文字内容'}</Text>
                  {source.subject && <Text type="secondary" ellipsis style={{ display: 'block' }}>{source.text}</Text>}
                  {!source.selectable && <>
                    <Text type="secondary" style={{ display: 'block' }}>{unavailableReason(source)}</Text>
                    {source.assignedTopicId && source.assignedTopicTitle?.trim() && (() => {
                      const assignedTopic = topics.find(topic => topic.id === source.assignedTopicId);
                      const isExpanded = expandedTopicId === source.assignedTopicId;
                      return <div style={{ marginTop: 4 }}>
                        <Text type="secondary">当前 Topic：</Text>
                        <Button
                          type="link"
                          size="small"
                          style={{ padding: 0, height: 'auto' }}
                          aria-expanded={isExpanded}
                          onClick={() => setExpandedTopicId(current => current === source.assignedTopicId ? null : source.assignedTopicId ?? null)}
                        >
                          {source.assignedTopicTitle.trim()}
                        </Button>
                        {isExpanded && <div
                          data-testid={`assigned-topic-${source.assignedTopicId}`}
                          className="cd-callout"
                        >
                          <Text strong style={{ display: 'block' }}>{assignedTopic?.title ?? source.assignedTopicTitle.trim()}</Text>
                          <Text type="secondary" style={{ display: 'block', marginTop: 4 }}>
                            {assignedTopic?.summary || '暂无 Topic 概要'}
                          </Text>
                          {assignedTopic && <>
                            <Text type="secondary" style={{ display: 'block', marginTop: 4 }}>
                              时间范围：{new Date(assignedTopic.firstOccurredAt).toLocaleString('zh-CN')} 至 {new Date(assignedTopic.lastOccurredAt).toLocaleString('zh-CN')}
                            </Text>
                            <Text type="secondary" style={{ display: 'block', marginTop: 2 }}>
                              {assignedTopic.sourceCount} 条来源
                            </Text>
                          </>}
                        </div>}
                      </div>;
                    })()}
                  </>}
                </div>
              </div>)}
            </Space>}
          <Button aria-label="生成 AI 预览" type="primary" icon={<RobotOutlined aria-hidden="true" />} disabled={selectedIds.length === 0} loading={loading === 'preview'} onClick={createPreview}>生成 AI 预览</Button>
        </>}
        {preview && <div>
          <Text strong>AI 整理预览</Text>
          <Space direction="vertical" size={12} style={{ width: '100%', marginTop: 8 }}>
            {assignments.map((assignment, index) => <div key={`${assignment.topicKey}-${index}`} className="cd-review-card">
              <Space style={{ marginBottom: 8 }}><Text strong>{assignment.title}</Text><Text type="secondary">{assignment.sourceIds.length} 条来源</Text></Space>
              <Space direction="vertical" style={{ width: '100%' }}>
              <Select aria-label={`目标 Topic ${index + 1}`} value={assignment.topicKey} style={{ width: '100%' }}
                onChange={topicKey => setAssignments(current => current.map((item, itemIndex) => itemIndex === index ? { ...item, topicKey } : item))}
                options={[
                  { value: `manual-new-${index}`, label: '新 Topic' },
                  ...topics.map(topic => ({ value: topic.id, label: topic.title })),
                ]} />
              <Input aria-label={`Topic 标题 ${index + 1}`} value={assignment.title}
                onChange={event => setAssignments(current => current.map((item, itemIndex) => itemIndex === index ? { ...item, title: event.target.value } : item))} />
              <Input.TextArea aria-label={`Topic 概要 ${index + 1}`} value={assignment.summary} autoSize={{ minRows: 2, maxRows: 5 }}
                onChange={event => setAssignments(current => current.map((item, itemIndex) => itemIndex === index ? { ...item, summary: event.target.value } : item))} />
              </Space>
            </div>)}
          </Space>
          <Button aria-label="确认应用" style={{ marginTop: 10 }} type="primary" icon={<CheckOutlined aria-hidden="true" />} loading={loading === 'apply'} onClick={applyPreview}>确认应用</Button>
        </div>}
      </Space>
    </div>}
  </div>;
}
