import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Input, List, Typography, Spin, Empty, message } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useUnifiedConversations, useMergeContacts } from '../hooks/useContacts';
import { useSse } from '../hooks/useSse';
import { useQueryClient } from '@tanstack/react-query';
import ContactCard from '../components/ContactCard';
import type { ConversationListItem } from '../api/types';
import { WechatOutlined } from '@ant-design/icons';

const { Text } = Typography;

export default function ContactsPage() {
  const [search, setSearch] = useState('');
  const [dragOverId, setDragOverId] = useState<string | null>(null);
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const navigate = useNavigate();
  const { contactId, sourceConversationId } = useParams();
  const qc = useQueryClient();

  const { data, isLoading } = useUnifiedConversations(search || undefined);
  const mergeMutation = useMergeContacts();

  useSse(() => {
    qc.invalidateQueries({ queryKey: ['conversations'] });
    qc.invalidateQueries({ queryKey: ['contacts'] });
  });

  const conversations = data?.records ?? [];

  const handleSelect = (id: string) => {
    navigate(`/conversations/contact/${id}`);
  };

  const handleDragStart = (e: React.DragEvent, id: string) => {
    e.dataTransfer.setData('contactId', id);
    e.dataTransfer.effectAllowed = 'move';
    setDraggingId(id);
  };

  const handleDragEnd = () => {
    setDraggingId(null);
    setDragOverId(null);
  };

  const handleDragOver = (e: React.DragEvent, id: string) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (id !== dragOverId) {
      setDragOverId(id);
    }
  };

  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOverId(null);
  };

  const handleDrop = async (e: React.DragEvent, targetId: string) => {
    e.preventDefault();
    setDragOverId(null);
    setDraggingId(null);
    const sourceId = e.dataTransfer.getData('contactId');
    if (sourceId && sourceId !== targetId) {
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
    }
  };

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div style={{ padding: '8px 12px' }}>
        <Input
          prefix={<SearchOutlined />}
          placeholder="搜索联系人、邮箱、号码"
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
          <Empty description="暂无联系人" style={{ padding: 24 }} />
        ) : (
          <List
            dataSource={conversations}
            renderItem={(conversation: ConversationListItem) => {
              if (conversation.type === 'WECOM_GROUP') {
                return (
                  <div
                    key={`group-${conversation.id}`}
                    onClick={() => navigate(`/conversations/wecom-group/${conversation.id}`)}
                    style={{ padding: '10px 12px', cursor: 'pointer', borderBottom: '1px solid #f0f0f0', background: sourceConversationId === conversation.id ? '#e6f4ff' : undefined }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <WechatOutlined />
                      <Text strong ellipsis style={{ flex: 1 }}>{conversation.displayName || '企业微信群'}</Text>
                      <Text type="secondary" style={{ fontSize: 11 }}>{conversation.participantCount} 人</Text>
                    </div>
                    {conversation.lastText && <Text type="secondary" ellipsis style={{ display: 'block', paddingLeft: 24, fontSize: 12 }}>{conversation.lastText}</Text>}
                  </div>
                );
              }
              const contact = conversation;
              return (
              <div
                key={`contact-${contact.id}`}
                draggable
                onDragStart={(e) => handleDragStart(e, contact.id)}
                onDragEnd={handleDragEnd}
                onDragOver={(e) => handleDragOver(e, contact.id)}
                onDragLeave={handleDragLeave}
                onDrop={(e) => handleDrop(e, contact.id)}
                style={{
                  opacity: draggingId === contact.id ? 0.4 : 1,
                  transition: 'opacity 0.15s, box-shadow 0.15s',
                  boxShadow: dragOverId === contact.id ? 'inset 0 0 0 2px #1677ff' : undefined,
                }}
              >
                <ContactCard
                  contact={contact}
                  isActive={contactId === contact.id}
                  onClick={() => handleSelect(contact.id)}
                />
              </div>
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
          拖拽合并
        </Text>
      </div>
    </div>
  );
}
