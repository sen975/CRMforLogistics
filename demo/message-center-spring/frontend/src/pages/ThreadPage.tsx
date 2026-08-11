import { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { Spin, Typography, Empty, Button, Tag } from 'antd';
import { ReloadOutlined, PhoneOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchThread, fetchTimeline, fetchContact, markContactRead } from '../api/endpoints';
import { useSse } from '../hooks/useSse';
import { useDetailPanel } from '../hooks/useDetailPanel';
import MessageBubble from '../components/MessageBubble';
import SendForm from '../components/SendForm';
import type { MessageResponse } from '../api/types';
import {
  CALL_RECORD_POLL_INTERVAL_MS,
  callRecordPlacement,
  hasActiveCallRecord,
} from '../utils/callRecordTimeline';

const { Text, Title } = Typography;

const PAGE_SIZE = 20;

interface CallRecordItem {
  kind: 'callRecord';
  id: string;
  occurredAt: string;
  direction: string;
  phonePointId: string;
  durationSeconds: number;
  state: string;
  errorCode: string;
  errorMessage: string;
  errorRetryable: boolean;
  attempts: number;
}

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
  const qc = useQueryClient();
  const containerRef = useRef<HTMLDivElement>(null);
  const prevScrollHeightRef = useRef(0);

  const [allItems, setAllItems] = useState<MessageResponse[]>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined>(undefined);
  const [hasMore, setHasMore] = useState(false);
  const [initialLoadDone, setInitialLoadDone] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [callRecords, setCallRecords] = useState<CallRecordItem[]>([]);
  const {
    selectedDetail,
    selectMessage,
    selectCallRecord,
    clearSelection,
  } = useDetailPanel();
  const selectedMessageId = selectedDetail?.kind === 'message' ? selectedDetail.id : null;
  const selectedCallRecordId = selectedDetail?.kind === 'callRecord' ? selectedDetail.id : null;

  const { data: contact } = useQuery({
    queryKey: ['contact', contactId],
    queryFn: () => fetchContact(contactId!),
    enabled: !!contactId,
  });

  const fetchPage = useCallback(async (cursor?: string) => {
    const result = await fetchThread(contactId!, { cursor, limit: PAGE_SIZE });
    return result;
  }, [contactId]);

  const loadCallRecords = useCallback(async (cid: string) => {
    try {
      const timeline = await fetchTimeline(cid, { limit: 100 });
      const items: CallRecordItem[] = timeline.items
        .filter((t) => t.type === 'callRecord')
        .map((t) => ({
          kind: 'callRecord' as const,
          id: t.payload.id as string,
          occurredAt: t.occurredAt,
          direction: t.payload.direction as string,
          phonePointId: t.payload.phonePointId as string,
          durationSeconds: t.payload.durationSeconds as number,
          state: t.payload.state as string,
          errorCode: (t.payload.errorCode as string) || '',
          errorMessage: (t.payload.errorMessage as string) || '',
          errorRetryable: (t.payload.errorRetryable as boolean) || false,
          attempts: (t.payload.attempts as number) || 0,
        }));
      setCallRecords(items);
    } catch {
      // timeline may not be available yet; keep existing call records
    }
  }, []);

  const refreshCallRecords = useCallback(() => {
    if (!contactId) {
      return Promise.resolve();
    }
    return loadCallRecords(contactId);
  }, [contactId, loadCallRecords]);

  useEffect(() => {
    if (!hasActiveCallRecord(callRecords)) {
      return;
    }

    let cancelled = false;
    let timer: number | undefined;
    const poll = async () => {
      await refreshCallRecords();
      if (!cancelled) {
        timer = window.setTimeout(poll, CALL_RECORD_POLL_INTERVAL_MS);
      }
    };
    timer = window.setTimeout(poll, CALL_RECORD_POLL_INTERVAL_MS);

    return () => {
      cancelled = true;
      if (timer !== undefined) {
        window.clearTimeout(timer);
      }
    };
  }, [callRecords, refreshCallRecords]);

  // Load initial page and reset on contact change
  useEffect(() => {
    if (!contactId) return;
    setAllItems([]);
    setCallRecords([]);
    setNextCursor(undefined);
    setHasMore(false);
    setInitialLoadDone(false);
    clearSelection();

    Promise.all([fetchPage(undefined), loadCallRecords(contactId)]).then(([result]) => {
      setAllItems(result.items);
      setNextCursor(result.nextCursor ?? undefined);
      setHasMore(!!result.nextCursor);
      setInitialLoadDone(true);
      markContactRead(contactId).then(() => {
        qc.invalidateQueries({ queryKey: ['contacts'] });
      });
    });
  }, [contactId]); // eslint-disable-line react-hooks/exhaustive-deps

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
      setLoadingOlder(true);
      prevScrollHeightRef.current = el.scrollHeight;

      fetchPage(nextCursor).then((result) => {
        setAllItems((prev) => [...result.items, ...prev]);
        setNextCursor(result.nextCursor ?? undefined);
        setHasMore(!!result.nextCursor);
        setLoadingOlder(false);
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
    qc.invalidateQueries({ queryKey: ['thread', contactId] });
    qc.invalidateQueries({ queryKey: ['contact', contactId] });
    Promise.all([fetchPage(undefined), loadCallRecords(contactId!)]).then(([result]) => {
      setAllItems(result.items);
      setNextCursor(result.nextCursor ?? undefined);
      setHasMore(!!result.nextCursor);
    });
  });

  const displayItems = useMemo(() => {
    const merged = [...allItems, ...callRecords];
    merged.sort((a, b) => new Date(a.occurredAt).getTime() - new Date(b.occurredAt).getTime());
    return merged;
  }, [allItems, callRecords]);

  if (!contactId) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}>
        <Empty description="选择一个联系人查看消息" />
      </div>
    );
  }

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div
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
            {contact?.displayName || contact?.remark || '加载中...'}
          </Title>
          {contact?.channelTypes && (
            <Text type="secondary" style={{ fontSize: 12 }}>
              {contact.channelTypes.join(' / ')}
            </Text>
          )}
        </div>
        <Button
          icon={<ReloadOutlined />}
          size="small"
          onClick={() => {
            setAllItems([]);
            setCallRecords([]);
            setNextCursor(undefined);
            setHasMore(false);
            setInitialLoadDone(false);
            Promise.all([fetchPage(undefined), loadCallRecords(contactId!)]).then(([result]) => {
              setAllItems(result.items);
              setNextCursor(result.nextCursor ?? undefined);
              setHasMore(!!result.nextCursor);
              setInitialLoadDone(true);
            });
          }}
        />
      </div>

      <div
        ref={containerRef}
        onScroll={handleScroll}
        style={{
          flex: 1,
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
        {displayItems.map((item) => {
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
              key={msg.id}
              message={msg}
              isActive={selectedMessageId === msg.id}
              onClick={() => {
                selectMessage(msg.id === selectedMessageId ? null : msg.id);
              }}
            />
          );
        })}
        {initialLoadDone && displayItems.length === 0 && <Empty description="暂无消息" />}
      </div>

      <div style={{ borderTop: '1px solid #f0f0f0', padding: 12, maxHeight: 260, overflow: 'auto' }}>
        <SendForm contact={contact} onCallRecordCreated={refreshCallRecords} />
      </div>
    </div>
  );
}
