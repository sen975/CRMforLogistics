import { Fragment, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Dropdown, Input, List, Typography, Spin, Empty, message, Tooltip } from 'antd';
import { DeleteOutlined, PushpinFilled, PushpinOutlined, SearchOutlined, WechatOutlined } from '@ant-design/icons';
import { useUnifiedConversations, useMergeContacts, useConversationPreference, useRestoreConversation } from '../hooks/useContacts';
import { useSse } from '../hooks/useSse';
import { useQueryClient } from '@tanstack/react-query';
import ContactCard from '../components/ContactCard';
import SearchModeSwitch from '../components/SearchModeSwitch';
import type { ConversationListItem, SearchMode } from '../api/types';
import { weComGroupDisplayName } from '../utils/weComGroupDisplayName';

const { Text } = Typography;

export default function ContactsPage() {
  const [search, setSearch] = useState('');
  const [searchMode, setSearchMode] = useState<SearchMode>('contact');
  const [dragOver, setDragOver] = useState<{ id: string; mode: 'before' | 'after' | 'merge' } | null>(null);
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const navigate = useNavigate();
  const { contactId, sourceConversationId } = useParams();
  const qc = useQueryClient();

  const { data, isLoading } = useUnifiedConversations(search || undefined, searchMode);
  const mergeMutation = useMergeContacts();
  const preferenceMutation = useConversationPreference();
  const restoreMutation = useRestoreConversation();
  const restoreAttempted = useRef<Set<string>>(new Set());

  // Deliberately opening a contact is intent to keep it reachable. A hidden contact is
  // absent from the list, so the first time a visit is observed we clear the stored
  // hidden preference. Marking the visit before the check keeps a later right-click
  // "delete" on the conversation that is open from being undone.
  const restoreMutate = restoreMutation.mutate;
  useEffect(() => {
    if (!contactId || search || isLoading || !data) return;
    if (restoreAttempted.current.has(contactId)) return;
    restoreAttempted.current.add(contactId);
    if (data.records.some((item) => item.type === 'CONTACT' && item.id === contactId)) return;
    restoreMutate({ targetType: 'CONTACT', targetId: contactId });
  }, [contactId, search, isLoading, data, restoreMutate]);

  const changeSearchMode = (mode: SearchMode) => {
    setSearchMode(mode);
    setSearch('');
  };

  useSse(() => {
    qc.invalidateQueries({ queryKey: ['conversations'] });
    qc.invalidateQueries({ queryKey: ['contacts'] });
  });

  const conversations = data?.records ?? [];

  const handleSelect = (id: string) => {
    navigate(`/conversations/contact/${id}`);
  };

  const handleDragStart = (e: React.DragEvent, id: string) => {
    if (search) {
      e.preventDefault();
      return;
    }
    e.dataTransfer.setData('contactId', id);
    const item = conversations.find((candidate) => candidate.id === id);
    if (item) e.dataTransfer.setData('conversationType', item.type);
    e.dataTransfer.effectAllowed = 'move';
    setDraggingId(id);
  };

  const handleDragEnd = () => {
    setDraggingId(null);
    setDragOver(null);
  };

  const handleDragOver = (e: React.DragEvent, id: string) => {
    if (search) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    const rect = e.currentTarget.getBoundingClientRect();
    const ratio = (e.clientY - rect.top) / Math.max(rect.height, 1);
    const source = conversations.find((candidate) => candidate.id === draggingId);
    const target = conversations.find((candidate) => candidate.id === id);
    const canMerge = source?.type === 'CONTACT' && target?.type === 'CONTACT';
    const mode = canMerge
      ? ratio < 0.3 ? 'before' : ratio > 0.7 ? 'after' : 'merge'
      : ratio < 0.5 ? 'before' : 'after';
    setDragOver((previous) => previous?.id === id && previous.mode === mode ? previous : { id, mode });
  };

  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    const nextTarget = e.relatedTarget;
    if (nextTarget instanceof Node && e.currentTarget.contains(nextTarget)) return;
    setDragOver(null);
  };

  const handleDrop = async (e: React.DragEvent, targetId: string, forcedMode?: 'before' | 'after') => {
    e.preventDefault();
    if (search) return;
    const mode = forcedMode ?? dragOver?.mode;
    setDragOver(null);
    setDraggingId(null);
    const sourceId = e.dataTransfer.getData('contactId');
    const sourceType = e.dataTransfer.getData('conversationType');
    const target = conversations.find((item) => item.id === targetId);
    if (sourceId && sourceId !== targetId && target && mode === 'merge') {
      if (sourceType !== 'CONTACT' || target.type !== 'CONTACT') {
        message.info('群聊不能与联系人合并');
        return;
      }
      try {
        await mergeMutation.mutateAsync({
          sourceContactId: sourceId,
          targetContactId: targetId,
        });
        message.success('联系人合并成功');
        qc.invalidateQueries({ queryKey: ['contacts'] });
        qc.invalidateQueries({ queryKey: ['thread', targetId] });
        qc.invalidateQueries({ queryKey: ['contact', targetId] });
        navigate(`/conversations/contact/${targetId}`);
      } catch (e: any) {
        const errMsg = e?.response?.data?.message || e?.message || '合并失败';
        message.error(errMsg);
      }
    } else if (sourceId && sourceId !== targetId && target && mode && mode !== 'merge') {
      const source = conversations.find((item) => item.id === sourceId);
      if (!source) return;
      try {
        await preferenceMutation.mutateAsync({
          action: 'order',
          sourceType: source.type,
          sourceId,
          targetType: target.type,
          targetId,
          placement: mode === 'before' ? 'BEFORE' : 'AFTER',
        });
      } catch (error: any) {
        message.error(error?.response?.data?.message || error?.message || '排序失败');
      }
    }
  };

  const handleContextAction = async (action: 'pin' | 'delete', conversation: ConversationListItem) => {
    try {
      await preferenceMutation.mutateAsync({ action, targetType: conversation.type, targetId: conversation.id });
      message.success(action === 'pin' ? (conversation.pinned ? '已取消置顶' : '已置顶') : '已从当前账号列表隐藏');
    } catch (error: any) {
      message.error(error?.response?.data?.message || error?.message || '操作失败');
    }
  };

  const contextMenu = (conversation: ConversationListItem) => ({
    items: [
      { key: 'pin', icon: <PushpinOutlined />, label: conversation.pinned ? '取消置顶' : '置顶' },
      { key: 'delete', icon: <DeleteOutlined />, label: '删除', danger: true },
    ],
    onClick: ({ key }: { key: string }) => void handleContextAction(key as 'pin' | 'delete', conversation),
  });

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div style={{ padding: '8px 12px' }}>
        <Input
          prefix={<SearchOutlined />}
          addonBefore={<SearchModeSwitch value={searchMode} onChange={changeSearchMode} />}
          placeholder={searchMode === 'tag' ? '搜索标签名' : '搜索联系人、邮箱、号码'}
          allowClear
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>
      <div style={{ flex: 1, overflow: 'auto' }}>
        {isLoading ? (
          <div style={{ textAlign: 'center', padding: 24 }}>
            <Spin />
          </div>
        ) : conversations.length === 0 ? (
          <Empty
            description={searchMode === 'tag'
              ? `没有联系人被打上含「${search}」的标签`
              : '暂无联系人'}
            style={{ padding: 24 }}
          />
        ) : (
          <List
            dataSource={conversations}
            renderItem={(conversation: ConversationListItem) => {
              const gap = (
                <div
                  data-testid={`conversation-gap-before-${conversation.id}`}
                  onDragOver={(e) => {
                    if (search || draggingId === conversation.id) return;
                    e.preventDefault();
                    e.dataTransfer.dropEffect = 'move';
                    setDragOver((previous) => previous?.id === conversation.id && previous.mode === 'before'
                      ? previous : { id: conversation.id, mode: 'before' });
                  }}
                  onDragLeave={(e) => {
                    e.preventDefault();
                    const nextTarget = e.relatedTarget;
                    if (nextTarget instanceof Node && e.currentTarget.contains(nextTarget)) return;
                    setDragOver(null);
                  }}
                  onDrop={(e) => void handleDrop(e, conversation.id, 'before')}
                  style={{
                    height: draggingId ? 10 : 0,
                    margin: draggingId ? '0 8px' : 0,
                    borderTop: dragOver?.id === conversation.id && dragOver.mode === 'before'
                      ? '2px solid #1677ff' : '2px solid transparent',
                    opacity: draggingId ? 1 : 0,
                    transition: 'height 0.15s, border-color 0.15s, opacity 0.15s',
                  }}
                />
              );
              if (conversation.type === 'WECOM_GROUP') {
                return (
                  <Fragment key={`group-${conversation.id}`}>
                    {gap}
                    <Dropdown trigger={['contextMenu']} menu={contextMenu(conversation)}>
                      <div
                        data-testid={`conversation-${conversation.id}`}
                        data-drop-mode={dragOver?.id === conversation.id ? dragOver.mode : undefined}
                        onClick={() => navigate(`/conversations/wecom-group/${conversation.id}`)}
                        draggable={!search}
                        onDragStart={(e) => handleDragStart(e, conversation.id)}
                        onDragEnd={handleDragEnd}
                        onDragOver={(e) => handleDragOver(e, conversation.id)}
                        onDragLeave={handleDragLeave}
                        onDrop={(e) => void handleDrop(e, conversation.id)}
                        style={{
                          padding: '10px 12px',
                          cursor: 'pointer',
                          borderBottom: '1px solid #f0f0f0',
                          opacity: draggingId === conversation.id ? 0.4 : 1,
                          boxShadow: dragOver?.id === conversation.id && dragOver.mode === 'merge'
                            ? '0 0 0 2px #fa8c16'
                            : undefined,
                          background: sourceConversationId === conversation.id ? '#e6f4ff' : undefined,
                          transform: dragOver?.id === conversation.id
                            ? dragOver.mode === 'before' ? 'translateY(2px)' : dragOver.mode === 'after' ? 'translateY(-2px)' : 'scale(0.99)'
                            : undefined,
                          transition: 'opacity 0.15s, background 0.2s, box-shadow 0.2s, transform 0.2s',
                        }}
                      >
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                          <WechatOutlined />
                          <Text strong ellipsis style={{ flex: 1 }}>{weComGroupDisplayName(conversation.displayName)}</Text>
                          {conversation.pinned ? (
                            <Tooltip title="已置顶">
                              <PushpinFilled aria-label="已置顶" style={{ color: '#1677ff', fontSize: 12 }} />
                            </Tooltip>
                          ) : null}
                          <Text type="secondary" style={{ fontSize: 11 }}>{conversation.participantCount} 人</Text>
                        </div>
                        {conversation.lastText && <Text type="secondary" ellipsis style={{ display: 'block', paddingLeft: 24, fontSize: 12 }}>{conversation.lastText}</Text>}
                      </div>
                    </Dropdown>
                  </Fragment>
                );
              }
              const contact = conversation;
              return (
              <Fragment key={`contact-${contact.id}`}>
                {gap}
                <div
                  data-testid={`conversation-${contact.id}`}
                  data-drop-mode={dragOver?.id === contact.id ? dragOver.mode : undefined}
                  draggable={!search}
                  onDragStart={(e) => handleDragStart(e, contact.id)}
                  onDragEnd={handleDragEnd}
                  onDragOver={(e) => handleDragOver(e, contact.id)}
                  onDragLeave={handleDragLeave}
                  onDrop={(e) => handleDrop(e, contact.id)}
                  style={{
                    opacity: draggingId === contact.id ? 0.4 : 1,
                    boxShadow: dragOver?.id === contact.id && dragOver.mode === 'merge'
                      ? '0 0 0 2px #fa8c16'
                      : undefined,
                    background: dragOver?.id === contact.id && dragOver.mode === 'merge' ? '#fff7e6' : undefined,
                    transform: dragOver?.id === contact.id
                      ? dragOver.mode === 'before' ? 'translateY(2px)' : dragOver.mode === 'after' ? 'translateY(-2px)' : 'scale(0.99)'
                      : undefined,
                    transition: 'opacity 0.15s, background 0.2s, box-shadow 0.2s, transform 0.2s',
                  }}
                >
                  <Dropdown trigger={['contextMenu']} menu={contextMenu(contact)}>
                    <div><ContactCard
                      contact={contact}
                      isActive={contactId === contact.id}
                      onClick={() => handleSelect(contact.id)}
                    /></div>
                  </Dropdown>
                </div>
              </Fragment>
              );
            }}
          />
        )}
      </div>
      <div style={{ padding: '4px 12px', borderTop: '1px solid #f0f0f0', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {data?.total ?? 0} 个会话
        </Text>
        <Text type="secondary" style={{ fontSize: 11 }}>
          拖动排序 · 中心重合合并
        </Text>
      </div>
    </div>
  );
}
