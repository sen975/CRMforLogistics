import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Input, List, Typography, Spin, Empty, message } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useContacts, useMergeContacts } from '../hooks/useContacts';
import { useSse } from '../hooks/useSse';
import { useQueryClient } from '@tanstack/react-query';
import ContactCard from '../components/ContactCard';

const { Text } = Typography;

export default function ContactsPage() {
  const [search, setSearch] = useState('');
  const [dragOverId, setDragOverId] = useState<string | null>(null);
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const navigate = useNavigate();
  const { contactId } = useParams();
  const qc = useQueryClient();

  const { data, isLoading } = useContacts(search || undefined);
  const mergeMutation = useMergeContacts();

  useSse(() => {
    qc.invalidateQueries({ queryKey: ['contacts'] });
  });

  const contacts = data?.records ?? [];

  const handleSelect = (id: string) => {
    navigate(`/thread/${id}`);
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
        navigate(`/thread/${targetId}`);
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
        ) : contacts.length === 0 ? (
          <Empty description="暂无联系人" style={{ padding: 24 }} />
        ) : (
          <List
            dataSource={contacts}
            renderItem={(contact) => (
              <div
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
            )}
          />
        )}
      </div>
      <div style={{ padding: '4px 12px', borderTop: '1px solid #f0f0f0', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {data?.total ?? 0} 个联系人
        </Text>
        <Text type="secondary" style={{ fontSize: 11 }}>
          拖拽合并
        </Text>
      </div>
    </div>
  );
}
