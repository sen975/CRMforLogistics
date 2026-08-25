import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Drawer, Input, List, Select, Space, Spin, Typography } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { getWeComExternalContact, listWeComDirectoryMembers, listWeComExternalContacts } from '../../api/endpoints';
import WeComProviderView from './WeComProviderView';
import { directoryMembers, externalContactIds } from './wecomProviderData';

const { Text } = Typography;

export default function WeComExternalContactPanel({ authCorpId }: { authCorpId: string }) {
  const [userId, setUserId] = useState('');
  const [activeUserId, setActiveUserId] = useState('');
  const [externalUserId, setExternalUserId] = useState('');
  const contacts = useQuery({
    queryKey: ['wecom', 'external-contacts', authCorpId, activeUserId],
    queryFn: () => listWeComExternalContacts(authCorpId, activeUserId),
    enabled: !!authCorpId && !!activeUserId,
    retry: false,
  });
  const directory = useQuery({
    queryKey: ['wecom', 'directory', 'members', authCorpId, 'all'],
    queryFn: () => listWeComDirectoryMembers(authCorpId, 1, true),
    enabled: !!authCorpId,
    retry: false,
  });
  const detail = useQuery({
    queryKey: ['wecom', 'external-contact', authCorpId, externalUserId],
    queryFn: () => getWeComExternalContact(authCorpId, externalUserId),
    enabled: !!authCorpId && !!externalUserId,
    retry: false,
  });
  return (
    <section className="wecom-panel" aria-label="客户联系管理">
      <div className="wecom-toolbar">
        <Select
          aria-label="客户联系成员"
          allowClear
          showSearch
          placeholder="选择通讯录成员"
          value={activeUserId || undefined}
          onChange={(value) => setActiveUserId(value ?? '')}
          loading={directory.isFetching}
          options={directoryMembers(directory.data).map((member) => ({ value: member.userId, label: `${member.name} (${member.userId})` }))}
          style={{ minWidth: 220 }}
        />
        <Input aria-label="客户联系成员 ID" value={userId} onChange={(event) => setUserId(event.target.value)}
          placeholder="输入内部成员 ID" onPressEnter={() => setActiveUserId(userId.trim())} />
        <Button aria-label="加载客户" icon={<SearchOutlined />} disabled={!userId.trim()} onClick={() => setActiveUserId(userId.trim())}>加载客户</Button>
      </div>
      <Spin spinning={contacts.isFetching}>
        {activeUserId && externalContactIds(contacts.data).length > 0 && (
          <List
            size="small"
            header={<Text strong>外部联系人 ({externalContactIds(contacts.data).length})</Text>}
            dataSource={externalContactIds(contacts.data)}
            renderItem={(externalId) => (
              <List.Item
                actions={[<Button key="detail" type="link" size="small" onClick={() => setExternalUserId(externalId)}>查看详情</Button>]}
              >
                <Text copyable={{ text: externalId }}>{externalId}</Text>
              </List.Item>
            )}
          />
        )}
        <WeComProviderView data={contacts.data} emptyText={activeUserId ? '该成员暂无客户' : '输入成员 ID 后加载客户'} />
      </Spin>
      {contacts.data && (
        <Space className="wecom-panel-actions">
          <Input aria-label="客户 External User ID" value={externalUserId}
            onChange={(event) => setExternalUserId(event.target.value)} placeholder="External User ID" />
          <Button disabled={!externalUserId.trim()} onClick={() => setExternalUserId(externalUserId.trim())}>查看详情</Button>
        </Space>
      )}
      <Drawer title="客户详情" open={!!externalUserId} onClose={() => setExternalUserId('')} width="min(560px, 100vw)">
        <Spin spinning={detail.isFetching}><WeComProviderView data={detail.data} /></Spin>
      </Drawer>
    </section>
  );
}
