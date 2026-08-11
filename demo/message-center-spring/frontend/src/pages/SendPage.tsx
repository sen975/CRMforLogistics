import { useState } from 'react';
import { Card, Select, Form, Typography, Empty } from 'antd';
import { useContacts } from '../hooks/useContacts';
import SendForm from '../components/SendForm';

const { Title } = Typography;

export default function SendPage() {
  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const { data } = useContacts(search || undefined);

  const contacts = data?.records ?? [];
  const selectedContact = contacts.find((c) => c.id === selectedId) ?? null;

  return (
    <div style={{ maxWidth: 640, margin: '0 auto', padding: 24 }}>
      <Title level={4}>发送消息</Title>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Form.Item label="收件人" style={{ marginBottom: 0 }}>
          <Select
            showSearch
            placeholder="搜索并选择联系人"
            filterOption={false}
            onSearch={setSearch}
            onChange={(id) => setSelectedId(id)}
            value={selectedId}
            style={{ width: '100%' }}
            options={contacts.map((c) => ({
              label: `${c.displayName || c.remark || '未命名'} (${c.channelTypes?.join(', ')})`,
              value: c.id,
            }))}
            notFoundContent={<Empty description="未找到联系人" />}
          />
        </Form.Item>
      </Card>
      {selectedContact ? (
        <SendForm contact={selectedContact} />
      ) : (
        <Empty description="先选择一个联系人" />
      )}
    </div>
  );
}
