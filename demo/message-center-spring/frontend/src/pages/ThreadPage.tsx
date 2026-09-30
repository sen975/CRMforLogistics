import { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Spin, Typography, Empty, Button, Tag } from 'antd';
import { ReloadOutlined, PhoneOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchThread, fetchContact, fetchWeComContactThread, markContactRead } from '../api/endpoints';
import { useSse } from '../hooks/useSse';
import { useDetailPanel } from '../hooks/useDetailPanel';
import MessageBubble from '../components/MessageBubble';
import SendForm from '../components/SendForm';
import type { MessageResponse } from '../api/types';
import { useCallRecordTimeline } from '../hooks/useCallRecordTimeline';
import type { CallRecordItem } from '../hooks/useCallRecordTimeline';
import { callRecordPlacement } from '../utils/callRecordTimeline';
import { segmentWeComTimeline, type WeComTimelineMode } from '../wecom/segmentWeComTimeline';
import { useWeComViewer } from '../hooks/useWeComViewer';
import { WeComConversationPanel } from '../components/wecom/WeComConversationPanel';
import { contactDisplayName } from '../utils/contactDisplayName';

const { Text, Title } = Typography;

const PAGE_SIZE = 20;

function formatSeconds(s: number) {
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  return `${m}:${sec.toString().padStart(2, '0')}`;
}

const stateTagMap: Record<string, { color: string; label: string }> = {
  queued: { color: 'default', label: '排队中' },
  processing: { color: 'processing', label: '转录中' },
  completed: { color: 'success', label: '已完成' },
  failed: { color: 'error', label: '失败' },
};

export default function ThreadPage() {
  const { contactId } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const containerRef = useRef<HTMLDivElement>(null);
  const prevScrollHeightRef = useRef(0);
  const weComViewer = useWeComViewer();
  const timelineScrollTopRef = useRef(0);
  const previousChannelRef = useRef<string | undefined>(undefined);
  const contactRequestGenerationRef = useRef(0);
  const activeContactIdRef = useRef<string | undefined>(contactId);
  const requestedChannel = searchParams.get('channel');
  const requestedIdentityId = searchParams.get('identityId') ?? undefined;

  const [allItems, setAllItems] = useState<MessageResponse[]>([]);
  const [itemsContactId, setItemsContactId] = useState<string | undefined>();
  const [nextCursor, setNextCursor] = useState<string | undefined>(undefined);
  const [hasMore, setHasMore] = useState(false);
  const [initialLoadDone, setInitialLoadDone] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const {
    selectedDetail,
    selectMessage,
    selectCallRecord,
    clearSelection,
    selectedChannel,
    selectChannel,
    clearChannel,
  } = useDetailPanel();
  const selectedMessageId = selectedDetail?.kind === 'message' ? selectedDetail.id : null;
  const selectedCallRecordId = selectedDetail?.kind === 'callRecord' ? selectedDetail.id : null;

  const { data: contact } = useQuery({
    queryKey: ['contact', contactId],
    queryFn: () => fetchContact(contactId!),
    enabled: !!contactId,
  });
  const {
    records: callRecords,
    refresh: refreshCallRecords,
  } = useCallRecordTimeline(contactId);
  const weComContactPointId = useMemo(() => {
    const identity = contact?.identities.find((item) => item.channelType === 'wecom');
    return identity ? `wecom:${identity.identityValue}` : '';
  }, [contact]);
  const { data: weComThread, refetch: refetchWeComContactThread } = useQuery({
    queryKey: ['wecom-contact-thread', contactId],
    queryFn: () => fetchWeComContactThread(contactId!, { limit: 50 }),
    enabled: !!contactId && !!weComContactPointId,
  });

  const fetchPage = useCallback(async (cursor?: string) => {
    const result = await fetchThread(contactId!, { cursor, limit: PAGE_SIZE });
    return result;
  }, [contactId]);

  // Load initial page and reset on contact change
  useEffect(() => {
    if (!contactId) return;
    const generation = ++contactRequestGenerationRef.current;
    activeContactIdRef.current = contactId;
    setAllItems([]);
    setItemsContactId(undefined);
    setNextCursor(undefined);
    setHasMore(false);
    setInitialLoadDone(false);
    setLoadingOlder(false);
    timelineScrollTopRef.current = 0;
    clearChannel();
    clearSelection();

    fetchPage(undefined).then((result) => {
      if (generation !== contactRequestGenerationRef.current) return;
      setAllItems(result.items);
      setItemsContactId(contactId);
      setNextCursor(result.nextCursor ?? undefined);
      setHasMore(!!result.nextCursor);
      setInitialLoadDone(true);
      markContactRead(contactId).then(() => {
        if (generation !== contactRequestGenerationRef.current) return;
        qc.invalidateQueries({ queryKey: ['contacts'] });
      });
    });
    return () => {
      if (contactRequestGenerationRef.current === generation) {
        contactRequestGenerationRef.current += 1;
      }
    };
  }, [contactId]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (requestedChannel === 'chatapp' || requestedChannel === 'email' || requestedChannel === 'phone') {
      selectChannel(requestedChannel);
    }
  }, [requestedChannel, selectChannel]);

  useEffect(() => {
    if (!contact || !weComContactPointId) return;
    const channels = new Set(contact.channelTypes ?? []);
    if (channels.size === 1 && channels.has('wecom') && selectedChannel !== 'wecom') {
      selectChannel('wecom');
    }
  }, [contact, selectedChannel, selectChannel, weComContactPointId]);

  const handleChannelChange = useCallback((channel: string) => {
    // 切渠道时消息列表的位置必须钉住：只有「从企微视图切回普通时间轴」才需要还原滚动 ——
    // 那时普通时间轴是一份重新挂载的 DOM，scrollTop 天然归零。此前对任何渠道都无条件还原，
    // 于是 邮件 ⇄ ChatApp ⇄ 电话记录 之间的切换也被按「上次离开企微时的位置」重置，
    // 表现就是切个 tab 消息自己跳走。
    const leavingWeCom = previousChannelRef.current === 'wecom' && channel !== 'wecom';
    if (channel === 'wecom' && weComContactPointId) {
      timelineScrollTopRef.current = containerRef.current?.scrollTop ?? 0;
    }
    previousChannelRef.current = channel;
    selectChannel(channel);
    if (leavingWeCom) {
      requestAnimationFrame(() => {
        if (containerRef.current) containerRef.current.scrollTop = timelineScrollTopRef.current;
      });
    }
  }, [selectChannel, weComContactPointId]);

  // Auto-scroll to bottom only after initial load
  useEffect(() => {
    if (initialLoadDone && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
      initialLoadDone && setInitialLoadDone(true);
    }
    // Only on initial load — not when loading older pages
  }, [initialLoadDone]);

  const handleScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el || !hasMore || loadingOlder) return;

    if (el.scrollTop < 80) {
      const generation = contactRequestGenerationRef.current;
      setLoadingOlder(true);
      prevScrollHeightRef.current = el.scrollHeight;

      fetchPage(nextCursor).then((result) => {
        if (generation !== contactRequestGenerationRef.current) return;
        setAllItems((prev) => [...result.items, ...prev]);
        setItemsContactId(contactId);
        setNextCursor(result.nextCursor ?? undefined);
        setHasMore(!!result.nextCursor);
        setLoadingOlder(false);
      }).catch(() => {
        if (generation === contactRequestGenerationRef.current) setLoadingOlder(false);
      });
    }
  }, [hasMore, loadingOlder, nextCursor, fetchPage]);

  // Maintain scroll position when older messages are prepended
  useEffect(() => {
    if (!loadingOlder && containerRef.current && prevScrollHeightRef.current > 0) {
      const el = containerRef.current;
      const newScrollHeight = el.scrollHeight;
      el.scrollTop = newScrollHeight - prevScrollHeightRef.current;
      prevScrollHeightRef.current = 0;
    }
  }, [loadingOlder]);

  useSse(() => {
    const generation = contactRequestGenerationRef.current;
    const activeContactId = activeContactIdRef.current;
    if (!activeContactId) return;
    qc.invalidateQueries({ queryKey: ['thread', activeContactId] });
    qc.invalidateQueries({ queryKey: ['wecom-contact-thread', activeContactId] });
    qc.invalidateQueries({ queryKey: ['contact', activeContactId] });
    qc.invalidateQueries({ queryKey: ['contact-topics', activeContactId] });
    void refreshCallRecords();
    fetchThread(activeContactId, { limit: PAGE_SIZE }).then((result) => {
      if (generation !== contactRequestGenerationRef.current) return;
      setAllItems(result.items);
      setItemsContactId(activeContactId);
      setNextCursor(result.nextCursor ?? undefined);
      setHasMore(!!result.nextCursor);
    });
  });

  const currentItems = itemsContactId === contactId ? allItems : [];
  const directContactItems = useMemo(
    () => currentItems.filter((item) => item.conversationType !== 'GROUP'),
    [currentItems],
  );
  const displayItems = useMemo(() => {
    const merged = [...directContactItems, ...callRecords];
    merged.sort((a, b) => new Date(a.occurredAt).getTime() - new Date(b.occurredAt).getTime());
    return merged;
  }, [directContactItems, callRecords]);
  const weComMode: WeComTimelineMode = useMemo(() => {
    const channels = new Set(contact?.channelTypes || []);
    return channels.size === 1 && channels.has('wecom') ? 'standalone' : 'mixed';
  }, [contact]);
  const timelineBlocks = useMemo(
    () => segmentWeComTimeline(displayItems, weComMode),
    [displayItems, weComMode],
  );
  const weComItems = useMemo(
    () => (weComThread?.items ?? []).filter((item) => (
      item.channelType === 'wecom' && item.conversationType !== 'GROUP' && !!item.sourceId
    )),
    [weComThread],
  );
  const showWeComConversation = selectedChannel === 'wecom' && !!weComContactPointId;
  const isPhoneTimeline = selectedChannel === 'phone';
  const canSwitchTimeline = (contact?.channelTypes ?? []).some((channel) => channel !== 'wecom');

  if (!contactId) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}>
        <Empty description="选择一个联系人查看消息" />
      </div>
    );
  }

  return (
    <div className="message-center-thread-shell" style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div
        className="message-center-thread-header"
        style={{
          padding: '12px 16px',
          borderBottom: '1px solid #f0f0f0',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <div>
          <Title level={5} style={{ margin: 0 }}>
            {contact ? contactDisplayName(contact) : '加载中...'}
          </Title>
        </div>
        <Button
          icon={<ReloadOutlined />}
          aria-label="刷新联系人会话"
          size="small"
          onClick={() => {
            const generation = contactRequestGenerationRef.current;
            setAllItems([]);
            setItemsContactId(undefined);
            setNextCursor(undefined);
            setHasMore(false);
            setInitialLoadDone(false);
            void refreshCallRecords();
            void refetchWeComContactThread();
            fetchPage(undefined).then((result) => {
              if (generation !== contactRequestGenerationRef.current) return;
              setAllItems(result.items);
              setItemsContactId(contactId);
              setNextCursor(result.nextCursor ?? undefined);
              setHasMore(!!result.nextCursor);
              setInitialLoadDone(true);
            });
          }}
        />
      </div>

      {showWeComConversation ? (
        <div className="message-center-thread-timeline-pane" style={{ flex: 4, minHeight: 0, minWidth: 0, width: '100%', display: 'flex' }}>
          <WeComConversationPanel
            contactPointId={weComContactPointId}
            items={weComItems}
            viewer={weComViewer}
            relatedGroups={weComThread?.relatedGroups ?? []}
            onSwitchToMixed={canSwitchTimeline ? clearChannel : undefined}
            onOpenGroup={(sourceConversationId) => {
              navigate(`/conversations/wecom-group/${encodeURIComponent(sourceConversationId)}`);
            }}
          />
        </div>
      ) : (
      <div
        className="message-center-thread-timeline"
        data-testid="thread-timeline"
        ref={containerRef}
        onScroll={handleScroll}
        style={{
          flex: 4,
          overflow: 'auto',
          padding: '12px 16px',
        }}
      >
        {!initialLoadDone && (
          <div style={{ textAlign: 'center', padding: 24 }}>
            <Spin />
          </div>
        )}
        {loadingOlder && (
          <div style={{ textAlign: 'center', padding: 8 }}>
            <Spin size="small" />
          </div>
        )}
        {timelineBlocks.map((block) => {
          if (block.kind === 'wecom') {
            return null;
          }
          const item = block.item;
          if ('kind' in item && item.kind === 'callRecord') {
            const record = item as CallRecordItem;
            const st = stateTagMap[record.state] ?? { color: 'default', label: record.state };
            const isOutbound = callRecordPlacement(record.direction) === 'right';
            return (
              <div
                key={`call-${record.id}`}
                onClick={() => selectCallRecord(record.id)}
                style={{
                  display: 'flex',
                  flexDirection: isOutbound ? 'row-reverse' : 'row',
                  gap: 8,
                  marginBottom: 12,
                  alignItems: 'flex-start',
                  cursor: 'pointer',
                }}
              >
                <div style={{ fontSize: 18, paddingTop: 4 }}>
                  <PhoneOutlined />
                </div>
                <div
                  style={{
                    maxWidth: 'min(320px, 80%)',
                    padding: '8px 12px',
                    borderRadius: 8,
                    background: isOutbound ? '#dcf8c6' : '#f0f0f0',
                    border: selectedCallRecordId === record.id
                      ? '2px solid #1677ff'
                      : '2px solid transparent',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                    <Tag color={record.direction === 'inbound' ? 'blue' : 'green'} style={{ margin: 0 }}>
                      {record.direction === 'inbound' ? '呼入' : '呼出'}
                    </Tag>
                    <Text style={{ fontSize: 12 }}>{record.phonePointId}</Text>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      {formatSeconds(record.durationSeconds)}
                    </Text>
                    <Tag color={st.color} style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}>{st.label}</Tag>
                    {record.state === 'failed' && record.errorMessage && (
                      <Text type="danger" style={{ fontSize: 10 }}>
                        {record.errorMessage.length > 16 ? record.errorMessage.slice(0, 16) + '…' : record.errorMessage}
                      </Text>
                    )}
                    {record.state === 'queued' && record.attempts > 0 && (
                      <Tag style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}>重试{record.attempts}</Tag>
                    )}
                  </div>
                  <Text type="secondary" style={{ fontSize: 10, display: 'block', marginTop: 4 }}>
                    {new Date(record.occurredAt).toLocaleString('zh-CN', {
                      month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
                    })}
                  </Text>
                </div>
              </div>
            );
          }
          const msg = item as MessageResponse;
          return (
            <MessageBubble
              key={block.id}
              message={msg}
              isActive={selectedMessageId === msg.id}
              onClick={() => {
                selectMessage(msg.id === selectedMessageId ? null : msg.id);
              }}
            />
          );
        })}
        {initialLoadDone && timelineBlocks.length === 0 && <Empty description="暂无消息" />}
      </div>
      )}

      {!showWeComConversation && !isPhoneTimeline && (
        <div className="message-center-thread-composer">
          <SendForm
            contact={contact}
            onCallRecordCreated={refreshCallRecords}
            activeChannel={selectedChannel ?? undefined}
            onChannelChange={handleChannelChange}
            selectedIdentityId={requestedIdentityId}
          />
        </div>
      )}
    </div>
  );
}
