import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { App, Button, Empty, Input, Pagination, Space, Typography } from 'antd';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import ChannelAddressBookForm from '../components/ChannelAddressBookForm';
import ChannelAddressBookList from '../components/ChannelAddressBookList';
import SearchModeSwitch from '../components/SearchModeSwitch';
import { useChannelAddressBook, useCreateManualChannelContact, useDeleteManualChannelContact } from '../hooks/useContacts';
import type { ChannelAddressBookChannel, SearchMode } from '../api/types';

const labels: Record<ChannelAddressBookChannel, string> = { chatapp: 'WhatsApp 通讯录', email: '邮件通讯录', phone: '电话通讯录' };
function isChannel(value: string | undefined): value is ChannelAddressBookChannel { return value === 'chatapp' || value === 'email' || value === 'phone'; }

export default function ChannelAddressBookPage() {
  const { channel: rawChannel } = useParams();
  const channel: ChannelAddressBookChannel = isChannel(rawChannel) ? rawChannel : 'chatapp';
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [searchMode, setSearchMode] = useState<SearchMode>('contact');
  const [formOpen, setFormOpen] = useState(false);
  const navigate = useNavigate();
  const { message } = App.useApp();
  const result = useChannelAddressBook(channel, search, page, searchMode);
  const create = useCreateManualChannelContact(channel);
  const remove = useDeleteManualChannelContact(channel);
  const open = (item: { contactId: string; identityId: string }) => navigate(`/conversations/contact/${item.contactId}?channel=${channel}&identityId=${item.identityId}`);
  const changeSearchMode = (mode: SearchMode) => {
    setSearchMode(mode);
    setQuery('');
    setSearch('');
    setPage(1);
  };
  return <div style={{ maxWidth: 1100, margin: '0 auto', padding: 16 }}>
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center' }}>
        <Typography.Title level={4} style={{ margin: 0 }}>{labels[channel]}</Typography.Title>
        <Button aria-label="新增联系人" type="primary" icon={<PlusOutlined />} onClick={() => setFormOpen(true)}>新增联系人</Button>
      </div>
      <Input.Search allowClear value={query} prefix={<SearchOutlined />}
        addonBefore={<SearchModeSwitch value={searchMode} onChange={changeSearchMode} />}
        placeholder={searchMode === 'tag' ? '搜索标签名' : '搜索名称、备注、号码或邮箱'}
        onChange={(event) => setQuery(event.target.value)}
        onSearch={(value) => { setSearch(value); setPage(1); }} />
      {result.data?.items.length === 0 && !result.isLoading
        ? <Empty description={searchMode === 'tag'
            ? `没有联系人被打上含「${search}」的标签`
            : '暂无联系人'} />
        : <ChannelAddressBookList channel={channel} items={result.data?.items ?? []} loading={result.isLoading} deletingId={remove.variables}
          onOpen={open} onDelete={(item) => remove.mutate(item.contactId, { onSuccess: () => message.success('已删除'), onError: () => message.error('无法删除已有记录的联系人') })} />}
      <Pagination current={page} pageSize={20} hideOnSinglePage={!result.data?.hasMore} showSizeChanger={false}
        total={result.data?.hasMore ? page * 20 + 1 : (page - 1) * 20 + (result.data?.items.length ?? 0)} onChange={setPage} />
    </Space>
    <ChannelAddressBookForm channel={channel} open={formOpen} loading={create.isPending} onCancel={() => setFormOpen(false)} onSubmit={(values) => create.mutate(values, { onSuccess: (item) => { setFormOpen(false); message.success('联系人已保存'); open(item); }, onError: () => message.error('联系人保存失败，请检查地址是否已存在') })} />
  </div>;
}
