import { useEffect, useMemo, useState } from 'react';
import { Card, Select, Form, Typography, Empty, Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useContacts } from '../hooks/useContacts';
import SendForm from '../components/SendForm';
import SearchModeSwitch from '../components/SearchModeSwitch';
import { fetchChannelCapabilities } from '../api/endpoints';
import type { SearchMode } from '../api/types';
import { contactDisplayName } from '../utils/contactDisplayName';

const { Title } = Typography;

export default function SendPage() {
  const [search, setSearch] = useState('');
  const [searchMode, setSearchMode] = useState<SearchMode>('contact');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedChannelAccountId, setSelectedChannelAccountId] = useState<string>();
  const {
    data: channelCapabilities,
    isPending: channelCapabilitiesPending,
    isError: channelCapabilitiesError,
  } = useQuery({
    queryKey: ['channelCapabilities'],
    queryFn: fetchChannelCapabilities,
  });
  const activeChatAppAccounts = useMemo(
    () => (channelCapabilities ?? []).filter((capability) => (
      (capability.channelType === 'chatapp' || capability.channelType === 'whatsapp')
      && capability.authStatus === 'active'
    )),
    [channelCapabilities],
  );
  useEffect(() => {
    if (activeChatAppAccounts.some(
      (capability) => capability.channelAccountId === selectedChannelAccountId,
    )) {
      return;
    }

    const replacementChannelAccountId = activeChatAppAccounts[0]?.channelAccountId;
    if (selectedChannelAccountId !== replacementChannelAccountId) {
      setSelectedChannelAccountId(replacementChannelAccountId);
      setSelectedId(null);
    }
  }, [activeChatAppAccounts, selectedChannelAccountId]);
  const currentChannelAccount = activeChatAppAccounts.find(
    (capability) => capability.channelAccountId === selectedChannelAccountId,
  );
  const currentChannelAccountId = currentChannelAccount?.channelAccountId;
  const {
    data,
    isPending: contactsPending,
    isError: contactsError,
  } = useContacts(
    search || undefined,
    1,
    20,
    { channelType: 'chatapp', channelAccountId: currentChannelAccountId },
    searchMode,
    { enabled: Boolean(currentChannelAccountId) },
  );

  const changeSearchMode = (mode: SearchMode) => {
    setSearchMode(mode);
    setSearch('');
  };

  const contacts = data?.records ?? [];
  const selectedContact = contacts.find((c) => c.id === selectedId) ?? null;

  if (channelCapabilitiesPending) {
    return <div style={{ padding: 24, textAlign: 'center' }}><Spin /></div>;
  }

  if (channelCapabilitiesError) {
    return <Empty description="无法加载 ChatApp 账号" style={{ padding: 24 }} />;
  }

  if (!currentChannelAccount) {
    return <Empty description="暂无可用 ChatApp 账号" style={{ padding: 24 }} />;
  }

  return (
    <div style={{ maxWidth: 640, margin: '0 auto', padding: 24 }}>
      <Title level={4}>发送消息</Title>
      <Card size="small" style={{ marginBottom: 16 }}>
        {activeChatAppAccounts.length > 1 && (
          <Form.Item label="ChatApp 账号" htmlFor="chatapp-account">
            <Select
              id="chatapp-account"
              value={currentChannelAccountId}
              onChange={(channelAccountId) => {
                setSelectedChannelAccountId(channelAccountId);
                setSelectedId(null);
              }}
              options={activeChatAppAccounts.map((capability) => ({
                label: capability.displayName,
                value: capability.channelAccountId,
              }))}
            />
          </Form.Item>
        )}
        <Form.Item label="收件人" style={{ marginBottom: 0 }}>
          <div style={{ display: 'flex', gap: 8 }}>
            <SearchModeSwitch value={searchMode} onChange={changeSearchMode} />
            <Select
              showSearch
              placeholder={searchMode === 'tag' ? '搜索标签名' : '搜索并选择联系人'}
              filterOption={false}
              onSearch={setSearch}
              onChange={(id) => setSelectedId(id)}
              value={selectedId}
              style={{ flex: 1 }}
              options={contacts.map((c) => ({
                label: searchMode === 'tag' && c.matchedTags?.length
                  ? `${contactDisplayName(c)} (${c.matchedTags.join('、')})`
                  : `${contactDisplayName(c)} (${c.channelTypes?.join(', ')})`,
                value: c.id,
              }))}
              notFoundContent={contactsPending ? <Spin size="small" /> : <Empty
                description={searchMode === 'tag'
                  ? `没有联系人被打上含「${search}」的标签`
                  : '暂无 CAMS 消息历史联系人'} />}
            />
          </div>
        </Form.Item>
      </Card>
      {contactsError ? (
        <Empty description="无法加载 CAMS 消息历史联系人" />
      ) : contactsPending ? (
        <div style={{ textAlign: 'center', padding: 24 }}><Spin /></div>
      ) : contacts.length === 0 ? (
        <Empty description="暂无 CAMS 消息历史联系人" />
      ) : selectedContact ? (
        <SendForm contact={selectedContact} selectedChannelAccountId={currentChannelAccountId} />
      ) : (
        <Empty description="先选择一个联系人" />
      )}
    </div>
  );
}
