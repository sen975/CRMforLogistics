import { useEffect, useState, type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import { Typography, Tag, Input, Button, Space, App, Spin, Empty, Popconfirm, Select, Tabs } from 'antd';
import {
  CheckOutlined,
  CloseOutlined,
  EditOutlined,
  FileTextOutlined,
  LinkOutlined,
  ScissorOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons';
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

const { Text } = Typography;

const channelLabels: Record<string, { label: string; color: string }> = {
  email: { label: '邮件', color: 'blue' },
  chatapp: { label: 'ChatApp', color: 'green' },
  wecom: { label: '企业微信', color: 'purple' },
  whatsapp: { label: 'WhatsApp', color: 'cyan' },
};

function formatFullTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN');
}

/* 详情面板的视觉语言取自设计稿（docs/ui-mockups/2026-09-29-workbench-v4.html 的 ctx 面板）：
   分组标题（图标 + 标题 + 计数胶囊）+ 无边框「键 / 值」行 + chip 标签 + 分组间细分隔线。
   刻意不用 antd Descriptions —— 它的有边框表格样式正是「像后台表单、不像工作台」的来源。 */
function CtxGroup({ icon, title, count, children }: { icon: ReactNode; title: string; count?: number; children: ReactNode }) {
  return (
    <section className="cd-group">
      <h3 className="cd-group-title">
        {icon}
        <span>{title}</span>
        {typeof count === 'number' && <span className="cd-cnt">{count}</span>}
      </h3>
      {children}
    </section>
  );
}

function CtxRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="cd-row">
      <span className="cd-k">{label}</span>
      <span className="cd-v">{children}</span>
    </div>
  );
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
    <div className="cd-body">
      <CtxGroup icon={<UserOutlined />} title="联系人">
        {/* 名称 = 渠道同步来的真名，故意不兜底备注：详情里「名称」「备注」是两行独立字段，
            若用 contactDisplayName()（列表语义、备注优先）两行会显示同一段文字。 */}
        <CtxRow label="名称">{contact.displayName || '-'}</CtxRow>
        <CtxRow label="备注">
          {editingRemark ? (
            <Space style={{ width: '100%' }}>
              <Input size="small" value={remarkValue} onChange={(e) => setRemarkValue(e.target.value)} style={{ flex: 1 }} autoFocus />
              <Button size="small" type="primary" icon={<CheckOutlined />} aria-label="保存备注" loading={updateRemark.isPending} onClick={handleSaveRemark} />
              <Button size="small" icon={<CloseOutlined />} aria-label="取消备注" onClick={() => setEditingRemark(false)} />
            </Space>
          ) : (
            <span className="cd-inline">
              <span className="cd-value-text">{contact.remark || '-'}</span>
              <Button type="text" size="small" className="cd-inline-edit" icon={<EditOutlined />} aria-label="编辑备注"
                onClick={() => { setRemarkValue(contact.remark || ''); setEditingRemark(true); }} />
            </span>
          )}
        </CtxRow>
        <CtxRow label="人工标签">
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
            <span className="cd-inline">
              <span className="cd-chips">
                {(contact.tags ?? []).length === 0 && <span className="cd-empty">-</span>}
                {(contact.tags ?? []).map((tag) => <span key={tag.id} className="cd-chip">{tag.name}</span>)}
              </span>
              <Button type="text" size="small" className="cd-inline-edit" icon={<EditOutlined />} aria-label="编辑标签"
                onClick={() => { setTagValues((contact.tags ?? []).map((tag) => tag.name)); setEditingTags(true); }} />
            </span>
          )}
        </CtxRow>
        <CtxRow label="AI 标签">
          <span className="cd-chips">
            {aiTags.length === 0 && <span className="cd-empty">暂无 AI 标签</span>}
            {aiTags.map((tag) => (
              <span
                key={tag.id}
                className="cd-chip cd-chip-ai"
                style={tag.status === 'STALE' ? { opacity: 0.55 } : undefined}
              >
                {tag.name}
              </span>
            ))}
            {aiTagsHasMore && (
              <Button type="link" size="small" loading={loadingMoreAiTags} onClick={loadMoreAiTags}>
                加载更多
              </Button>
            )}
          </span>
        </CtxRow>
        <CtxRow label="最后消息">{contact.lastMessageAt ? new Date(contact.lastMessageAt).toLocaleString('zh-CN') : '-'}</CtxRow>
        <CtxRow label="未读数">{contact.unreadCount}</CtxRow>
      </CtxGroup>
      <CtxGroup icon={<ThunderboltOutlined />} title="AI 画像">
        <p className={`cd-prose${contact.memory?.profile?.content ? '' : ' is-empty'}`}>
          {contact.memory?.profile?.content || '暂无画像'}
        </p>
        {(contact.memory?.state === 'DIRTY' || contact.memory?.state === 'PROCESSING') && <p className="cd-hint">正在更新</p>}
        {contact.memory?.state === 'FAILED' && <p className="cd-hint">画像更新失败，请稍后重试</p>}
      </CtxGroup>
    </div>
  );

  const accountChannels = (
    <div className="cd-body">
      <CtxGroup icon={<LinkOutlined />} title="渠道身份" count={(contact.identities ?? []).length}>
        <CtxRow label="渠道类型">
          <span className="cd-chips">
            {contact.channelTypes?.map((ch) => {
              const info = channelLabels[ch] ?? { label: ch, color: 'default' };
              return <Tag key={ch} color={info.color}>{info.label}</Tag>;
            })}
          </span>
        </CtxRow>
        {(contact.identities ?? []).length === 0 && (
          <CtxRow label="已绑定账号"><span className="cd-empty">暂无绑定账号</span></CtxRow>
        )}
        {(contact.identities ?? []).map((identity) => (
          <div key={identity.id} className="cd-row cd-row-link" onClick={() => handleSelectChannel(identity.channelType)}>
            <span className="cd-k">{channelLabels[identity.channelType]?.label ?? identity.channelType}</span>
            <span className="cd-v cd-v-identity">
              <span className="cd-identity-main">
                <b className="cd-identity-name">{identity.displayName || identity.identityValue}</b>
                {identity.channelType !== 'wecom' && (
                  <span className="cd-identity-value">
                    {identity.channelType === 'chatapp' ? identity.identityValue : `${identity.identityScope}: ${identity.identityValue}`}
                  </span>
                )}
              </span>
              {/* 点「拆分」不能顺带把渠道也切了 —— 行本身可点，所以这里要拦冒泡。 */}
              {contact.identities.length > 1 && (
                <Popconfirm title="确定要拆分此账号吗？" description="该账号将成为一个独立的联系人"
                  onConfirm={() => handleSplit(identity)} okText="确定" cancelText="取消">
                  <Button size="small" type="text" danger icon={<ScissorOutlined />} loading={splitMutation.isPending}
                    aria-label={`拆分 ${identity.displayName || identity.identityValue}`}
                    onClick={(event) => event.stopPropagation()}>拆分</Button>
                </Popconfirm>
              )}
            </span>
          </div>
        ))}
      </CtxGroup>
    </div>
  );

  const messageInfo = (
    <div className="cd-body">
      <CtxGroup icon={<FileTextOutlined />} title="消息详情">
        {!selectedMessageId ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请从消息或 Topic 来源中选择一条消息" /> : messageDetail ? (
          <>
            <CtxRow label="方向"><Tag color={messageDetail.direction === 'inbound' ? 'blue' : 'green'}>{messageDetail.direction === 'inbound' ? '接收' : '发送'}</Tag></CtxRow>
            <CtxRow label="类型"><Tag>{messageDetail.kind}</Tag></CtxRow>
            <CtxRow label="渠道"><Tag>{messageDetail.channelType}</Tag></CtxRow>
            <CtxRow label="发送方">{messageDetail.from}</CtxRow>
            <CtxRow label="接收方">{messageDetail.to}</CtxRow>
            <CtxRow label="时间">{formatFullTime(messageDetail.occurredAt)}</CtxRow>
            <CtxRow label="状态"><Tag>{messageDetail.status}</Tag></CtxRow>
            {messageDetail.subject && <CtxRow label="主题"><Text strong>{messageDetail.subject}</Text></CtxRow>}
            <CtxRow label="内容">
              <div className="cd-message-body">
                {(messageDetail.kind === 'email' || messageDetail.channelType === 'email') && messageDetail.bodyHtml
                  ? <div dangerouslySetInnerHTML={{ __html: messageDetail.bodyHtml }} />
                  : <Text>{decodeHtmlEntities(messageDetail.bodyText)}</Text>}
              </div>
            </CtxRow>
            <CtxRow label="附件"><EmailAttachmentList attachments={messageDetail.attachments ?? []} /></CtxRow>
          </>
        ) : <div style={{ textAlign: 'center', padding: 8 }}><Spin size="small" /></div>}
      </CtxGroup>
    </div>
  );

  return (
    <div className="mc-detail-panel" style={{ width: '100%', height: '100%', minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
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
