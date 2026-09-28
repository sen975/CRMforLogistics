import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Typography, Tag, Descriptions, Input, Button, Space, App, Spin, Empty, Popconfirm, List, Select, Tabs } from 'antd';
import { EditOutlined, CheckOutlined, CloseOutlined, ScissorOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { applyTopicFusion, fetchContact, fetchContactMemory, fetchMessage, fetchManualReviewPending, keepPendingTopic, previewTopicFusion } from '../api/endpoints';
import { useUpdateContactRemark, useUpdateContactTags, useSplitContact } from '../hooks/useContacts';
import { useDetailPanel } from '../hooks/useDetailPanel';
import type { ContactIdentityResponse, ContactMemoryAiTag } from '../api/types';
import EmailAttachmentList from './EmailAttachmentList';
import AiTopicTimeline from './AiTopicTimeline';
import AiTopicManualReviewPanel from './AiTopicManualReviewPanel';
import { useTopicTimeline } from '../hooks/useTopicTimeline';
import { decodeHtmlEntities } from '../utils/htmlEntities';

const { Text, Title } = Typography;

const channelLabels: Record<string, { label: string; color: string }> = {
  email: { label: '邮件', color: 'blue' },
  chatapp: { label: 'ChatApp', color: 'green' },
  wecom: { label: '企业微信', color: 'purple' },
  whatsapp: { label: 'WhatsApp', color: 'cyan' },
};

function formatFullTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN');
}

export default function ContactDetailPanel() {
  const { contactId } = useParams();
  const qc = useQueryClient();
  const { message: appMessage } = App.useApp();
  const [editingRemark, setEditingRemark] = useState(false);
  const [remarkValue, setRemarkValue] = useState('');
  const [editingTags, setEditingTags] = useState(false);
  const [tagValues, setTagValues] = useState<string[]>([]);
  const [activeTab, setActiveTab] = useState('topics');
  const [aiTags, setAiTags] = useState<ContactMemoryAiTag[]>([]);
  const [aiTagsCursor, setAiTagsCursor] = useState<string | null>(null);
  const [aiTagsHasMore, setAiTagsHasMore] = useState(false);
  const [loadingMoreAiTags, setLoadingMoreAiTags] = useState(false);
  const { selectedDetail, selectChannel, selectMessage, selectCallRecord } = useDetailPanel();
  const selectedMessageId = selectedDetail?.kind === 'message' ? selectedDetail.id : null;

  useEffect(() => {
    setActiveTab('topics');
  }, [contactId]);

  useEffect(() => {
    if (selectedDetail?.kind === 'message') setActiveTab('message');
  }, [selectedDetail]);

  const { data: contact, isLoading } = useQuery({
    queryKey: ['contact', contactId],
    queryFn: () => fetchContact(contactId!),
    enabled: !!contactId,
  });

  const { data: messageDetail } = useQuery({
    queryKey: ['message', selectedMessageId],
    queryFn: () => fetchMessage(selectedMessageId!),
    enabled: !!selectedMessageId,
  });

  useEffect(() => {
    const memory = contact?.memory;
    setAiTags(memory?.aiTags ?? []);
    setAiTagsCursor(memory?.aiTagsNextCursor ?? null);
    setAiTagsHasMore(memory?.aiTagsHasMore ?? false);
  }, [contact?.id, contact?.memory]);

  const updateRemark = useUpdateContactRemark();
  const updateTags = useUpdateContactTags();
  const splitMutation = useSplitContact();
  const topicTimeline = useTopicTimeline(contactId);
  const pendingTopics = useQuery({ queryKey: ['topic-review-pending', contactId], queryFn: () => fetchManualReviewPending(contactId!), enabled: !!contactId });
  const refreshTopicSections = () => {
    void qc.invalidateQueries({ queryKey: ['topic-review-pending', contactId] });
    void qc.invalidateQueries({ queryKey: ['contact-topics', contactId] });
  };
  const keepPending = useMutation({ mutationFn: keepPendingTopic, onSuccess: refreshTopicSections });
  const fusionPreview = useMutation({ mutationFn: ({ topicIds, expectedVersions }: { topicIds: string[]; expectedVersions: Record<string, number> }) =>
    previewTopicFusion(contactId!, { topicIds, expectedVersions }) });
  const fusionApply = useMutation({ mutationFn: (previewId: string) => applyTopicFusion(contactId!, previewId), onSuccess: refreshTopicSections });

  const handleSplit = async (identity: ContactIdentityResponse) => {
    try {
      await splitMutation.mutateAsync({
        identityId: identity.id,
        newContactName: identity.displayName || identity.identityValue,
      });
      qc.invalidateQueries({ queryKey: ['contact', contactId] });
      qc.invalidateQueries({ queryKey: ['contacts'] });
      appMessage.success('账号已拆分');
    } catch (e: any) {
      const errMsg = e?.response?.data?.message || e?.message || '拆分失败';
      appMessage.error(errMsg);
    }
  };

  if (!contactId) {
    return (
      <div style={{ padding: 16 }}>
        <Text type="secondary">选择联系人查看详情</Text>
      </div>
    );
  }

  if (isLoading || !contact) {
    return (
      <div style={{ textAlign: 'center', padding: 24 }}>
        <Spin />
      </div>
    );
  }

  const handleSaveRemark = async () => {
    try {
      await updateRemark.mutateAsync({ id: contact.id, remark: remarkValue });
      qc.invalidateQueries({ queryKey: ['contact', contactId] });
      appMessage.success('备注已更新');
      setEditingRemark(false);
    } catch {
      appMessage.error('更新失败');
    }
  };

  const handleSelectChannel = (channelType: string) => {
    setActiveTab('accounts');
    selectChannel(channelType);
  };

  const loadMoreAiTags = async () => {
    if (!contact || !aiTagsCursor || loadingMoreAiTags) return;
    setLoadingMoreAiTags(true);
    try {
      const next = await fetchContactMemory(contact.id, { limit: 100, cursor: aiTagsCursor });
      setAiTags((current) => [...current, ...(next.aiTags ?? [])]);
      setAiTagsCursor(next.aiTagsNextCursor ?? null);
      setAiTagsHasMore(next.aiTagsHasMore ?? false);
    } finally {
      setLoadingMoreAiTags(false);
    }
  };

  const tabContentStyle = { padding: 16, minHeight: 0 };
  const contactInfo = (
    <div style={tabContentStyle}>
      <Title level={5} style={{ margin: '0 0 16px' }}>联系人信息</Title>
      <Descriptions column={1} size="small" bordered>
        {/* 名称 = 渠道同步来的真名，故意不兜底备注：详情里「名称」「备注」是两行独立字段，
            若用 contactDisplayName()（列表语义、备注优先）两行会显示同一段文字。 */}
        <Descriptions.Item label="名称">{contact.displayName || '-'}</Descriptions.Item>
        <Descriptions.Item label="备注">
          {editingRemark ? (
            <Space style={{ width: '100%' }}>
              <Input size="small" value={remarkValue} onChange={(e) => setRemarkValue(e.target.value)} style={{ flex: 1 }} autoFocus />
              <Button size="small" type="primary" icon={<CheckOutlined />} loading={updateRemark.isPending} onClick={handleSaveRemark} />
              <Button size="small" icon={<CloseOutlined />} onClick={() => setEditingRemark(false)} />
            </Space>
          ) : (
            <Space>
              <Text>{contact.remark || '-'}</Text>
              <Button type="link" size="small" icon={<EditOutlined />} onClick={() => { setRemarkValue(contact.remark || ''); setEditingRemark(true); }} />
            </Space>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="人工标签">
          {editingTags ? (
            <Space style={{ width: '100%' }}>
              <Select mode="tags" value={tagValues} onChange={setTagValues} style={{ minWidth: 160, flex: 1 }}
                options={(contact.tags ?? []).map((tag) => ({ value: tag.name, label: tag.name }))} placeholder="输入标签后回车" />
              <Button size="small" type="primary" icon={<CheckOutlined />} aria-label="保存标签" loading={updateTags.isPending}
                onClick={async () => {
                  try {
                    await updateTags.mutateAsync({ id: contact.id, tags: tagValues.map((name) => ({ name })) });
                    appMessage.success('标签已更新');
                    setEditingTags(false);
                  } catch {
                    appMessage.error('标签更新失败');
                  }
                }} />
              <Button size="small" icon={<CloseOutlined />} aria-label="取消标签" onClick={() => setEditingTags(false)} />
            </Space>
          ) : (
            <Space wrap>
              {(contact.tags ?? []).length === 0 && <Text type="secondary">-</Text>}
              {(contact.tags ?? []).map((tag) => <Tag key={tag.id} color={tag.color || undefined}>{tag.name}</Tag>)}
              <Button type="link" size="small" icon={<EditOutlined />} aria-label="编辑标签"
                onClick={() => { setTagValues((contact.tags ?? []).map((tag) => tag.name)); setEditingTags(true); }} />
              </Space>
            )}
        </Descriptions.Item>
        <Descriptions.Item label="AI 画像">
          <Space direction="vertical" size={4}>
            <Text type={contact.memory?.profile ? undefined : 'secondary'}>
              {contact.memory?.profile?.content || '暂无画像'}
            </Text>
            {(contact.memory?.state === 'DIRTY' || contact.memory?.state === 'PROCESSING') && (
              <Text type="secondary">正在更新</Text>
            )}
            {contact.memory?.state === 'FAILED' && (
              <Text type="secondary">画像更新失败，请稍后重试</Text>
            )}
          </Space>
        </Descriptions.Item>
        <Descriptions.Item label="AI 标签">
          <Space wrap>
            {aiTags.length === 0 && <Text type="secondary">暂无 AI 标签</Text>}
            {aiTags.map((tag) => (
              <Tag
                key={tag.id}
                color={tag.colorToken || undefined}
                style={tag.status === 'STALE' ? { opacity: 0.55 } : undefined}
              >
                {tag.name}
              </Tag>
            ))}
            {aiTagsHasMore && (
              <Button type="link" size="small" loading={loadingMoreAiTags} onClick={loadMoreAiTags}>
                加载更多
              </Button>
            )}
          </Space>
        </Descriptions.Item>
        <Descriptions.Item label="最后消息">{contact.lastMessageAt ? new Date(contact.lastMessageAt).toLocaleString('zh-CN') : '-'}</Descriptions.Item>
        <Descriptions.Item label="未读数">{contact.unreadCount}</Descriptions.Item>
      </Descriptions>
    </div>
  );

  const accountChannels = (
    <div style={tabContentStyle}>
      <Title level={5} style={{ margin: '0 0 16px' }}>账号渠道</Title>
      <Descriptions column={1} size="small" bordered>
        <Descriptions.Item label="渠道">
          <Space size={4} wrap>
            {contact.channelTypes?.map((ch) => {
              const info = channelLabels[ch] ?? { label: ch, color: 'default' };
              return <Tag key={ch} color={info.color}>{info.label}</Tag>;
            })}
          </Space>
        </Descriptions.Item>
      </Descriptions>
      {contact.identities && contact.identities.length > 0 ? (
        <List
          size="small"
          header={<Text strong>已绑定账号</Text>}
          dataSource={contact.identities}
          renderItem={(identity) => (
            <List.Item
              onClick={() => handleSelectChannel(identity.channelType)}
              style={{ cursor: 'pointer' }}
              actions={[
                contact.identities.length > 1 ? (
                  <Popconfirm key="split" title="确定要拆分此账号吗？" description="该账号将成为一个独立的联系人"
                    onConfirm={() => handleSplit(identity)} okText="确定" cancelText="取消">
                    <Button size="small" type="link" danger icon={<ScissorOutlined />} loading={splitMutation.isPending}>拆分</Button>
                  </Popconfirm>
                ) : null,
              ].filter(Boolean)}
            >
              <List.Item.Meta
                title={<Space size={4}><Tag color={channelLabels[identity.channelType]?.color ?? 'default'}>{channelLabels[identity.channelType]?.label ?? identity.channelType}</Tag><Text>{identity.displayName || identity.identityValue}</Text></Space>}
                description={identity.channelType === 'wecom' ? undefined : (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {identity.channelType === 'chatapp' ? identity.identityValue : `${identity.identityScope}: ${identity.identityValue}`}
                  </Text>
                )}
              />
            </List.Item>
          )}
        />
      ) : <Text type="secondary">暂无绑定账号</Text>}
    </div>
  );

  const messageInfo = (
    <div style={tabContentStyle}>
      <Title level={5} style={{ margin: '0 0 12px' }}>消息详情</Title>
      {!selectedMessageId ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请从消息或 Topic 来源中选择一条消息" /> : messageDetail ? (
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label="方向"><Tag color={messageDetail.direction === 'inbound' ? 'blue' : 'green'}>{messageDetail.direction === 'inbound' ? '接收' : '发送'}</Tag></Descriptions.Item>
          <Descriptions.Item label="类型"><Tag>{messageDetail.kind}</Tag></Descriptions.Item>
          <Descriptions.Item label="渠道"><Tag>{messageDetail.channelType}</Tag></Descriptions.Item>
          <Descriptions.Item label="发送方">{messageDetail.from}</Descriptions.Item>
          <Descriptions.Item label="接收方">{messageDetail.to}</Descriptions.Item>
          <Descriptions.Item label="时间">{formatFullTime(messageDetail.occurredAt)}</Descriptions.Item>
          <Descriptions.Item label="状态"><Tag>{messageDetail.status}</Tag></Descriptions.Item>
          {messageDetail.subject && <Descriptions.Item label="主题"><Text strong>{messageDetail.subject}</Text></Descriptions.Item>}
          <Descriptions.Item label="内容">
            <div style={{ maxHeight: 300, overflow: 'auto', whiteSpace: 'pre-wrap', fontSize: 13 }}>
              {(messageDetail.kind === 'email' || messageDetail.channelType === 'email') && messageDetail.bodyHtml
                ? <div dangerouslySetInnerHTML={{ __html: messageDetail.bodyHtml }} />
                : <Text>{decodeHtmlEntities(messageDetail.bodyText)}</Text>}
            </div>
          </Descriptions.Item>
          <Descriptions.Item label="附件"><EmailAttachmentList attachments={messageDetail.attachments ?? []} /></Descriptions.Item>
        </Descriptions>
      ) : <div style={{ textAlign: 'center', padding: 8 }}><Spin size="small" /></div>}
    </div>
  );

  return (
    <div className="contact-detail-panel" style={{ width: '100%', height: '100%', minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <Tabs
        className="detail-panel-tabs"
        activeKey={activeTab}
        onChange={setActiveTab}
        size="small"
        destroyOnHidden
        data-testid="contact-detail-tabs"
        style={{ width: '100%', height: '100%', minWidth: 0, minHeight: 0 }}
        tabBarStyle={{ margin: 0, paddingInline: 8, flex: '0 0 auto' }}
        items={[
          {
            key: 'topics',
            label: 'Topic 时间轴',
            children: <div style={tabContentStyle}>
              <AiTopicTimeline
                contactId={contact.id}
                timeline={topicTimeline.data}
                actions={{ update: topicTimeline.update, merge: topicTimeline.merge, store: topicTimeline.store, retry: topicTimeline.retry }}
                pendingTopics={pendingTopics.data ?? []}
                onKeepPending={(topicId) => keepPending.mutate(topicId)}
                onPreviewFusion={(topicIds, expectedVersions) => fusionPreview.mutateAsync({ topicIds, expectedVersions })}
                onApplyFusion={(previewId) => fusionApply.mutateAsync(previewId).then(() => undefined)}
                onSourceClick={(source) => source.sourceType === 'MESSAGE' ? selectMessage(source.id) : source.sourceType === 'CALL_RECORD' ? selectCallRecord(source.id) : undefined}
              />
              <AiTopicManualReviewPanel contactId={contact.id} identities={contact.identities ?? []}
                topics={topicTimeline.data?.topics ?? []} onApplied={refreshTopicSections} />
            </div>,
          },
          { key: 'contact', label: '联系人信息', children: contactInfo },
          { key: 'accounts', label: '账号渠道', children: accountChannels },
          { key: 'message', label: '消息详情', children: messageInfo },
        ]}
      />
    </div>
  );
}
