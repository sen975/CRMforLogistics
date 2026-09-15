import { DeleteOutlined, MailOutlined, MessageOutlined, PhoneOutlined } from '@ant-design/icons';
import { Button, Popconfirm, Space, Table, Tag, Typography } from 'antd';
import type { ChannelAddressBookItem, ChannelAddressBookChannel } from '../api/types';

const iconByChannel = { chatapp: <MessageOutlined />, email: <MailOutlined />, phone: <PhoneOutlined /> };
const labelByChannel: Record<string, string> = { chatapp: 'WhatsApp', email: '邮件', phone: '电话', wecom: '企业微信' };

interface Props {
  channel: ChannelAddressBookChannel;
  items: ChannelAddressBookItem[];
  loading?: boolean;
  deletingId?: string;
  onOpen: (item: ChannelAddressBookItem) => void;
  onDelete: (item: ChannelAddressBookItem) => void;
}

export default function ChannelAddressBookList({ channel, items, loading, deletingId, onOpen, onDelete }: Props) {
  return <Table<ChannelAddressBookItem> rowKey="identityId" size="middle" loading={loading} dataSource={items}
    pagination={false} onRow={(item) => ({ onClick: () => onOpen(item), style: { cursor: 'pointer' } })}
    columns={[
      { title: '联系人', key: 'contact', render: (_, item) => <Space>{iconByChannel[channel]}<span>{item.displayName}</span>
        {item.matchedTags?.map((name) => <Tag key={name} color="blue" style={{ fontSize: 10, lineHeight: '16px' }}>{name}</Tag>)}</Space> },
      { title: channel === 'email' ? '邮箱' : '号码', dataIndex: 'address', key: 'address', render: (address) => <Typography.Text type="secondary">{address}</Typography.Text> },
      { title: '其他渠道', key: 'additional', render: (_, item) => <Space size={4}>{item.additionalChannelTypes.map((type) => <Tag key={type}>【{labelByChannel[type] ?? type}】</Tag>)}</Space> },
      { title: '最近联系', key: 'lastContactAt', width: 180, render: (_, item) => item.lastContactAt ? new Date(item.lastContactAt).toLocaleString('zh-CN') : '尚无记录' },
      { title: '操作', key: 'actions', width: 70, render: (_, item) => item.canDelete ? <Popconfirm title="删除此人工联系人？" onConfirm={(event) => { event?.stopPropagation(); onDelete(item); }}><Button type="text" danger aria-label="删除联系人" icon={<DeleteOutlined />} loading={deletingId === item.contactId} onClick={(event) => event.stopPropagation()} /></Popconfirm> : null },
    ]}
  />;
}
