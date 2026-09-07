import { Badge, Typography, Space, Tag, Tooltip } from 'antd';
import { MailOutlined, MessageOutlined, PhoneOutlined, PushpinFilled, WechatOutlined } from '@ant-design/icons';
import type { ContactResponse } from '../api/types';
import { contactDisplayName } from '../utils/contactDisplayName';

const { Text } = Typography;

const channelIcons: Record<string, React.ReactNode> = {
  email: <MailOutlined />,
  chatapp: <MessageOutlined />,
  wecom: <WechatOutlined />,
  phone: <PhoneOutlined />,
  call: <PhoneOutlined />,
};

function formatTime(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const now = new Date();
  const diff = now.getTime() - d.getTime();
  if (diff < 86400000 && d.getDate() === now.getDate()) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  }
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
}

interface ContactCardProps {
  contact: Pick<ContactResponse, 'id' | 'displayName' | 'channelTypes' | 'lastMessageAt' | 'lastText' | 'messageCount' | 'unreadCount'> & { remark?: string | null; pinned?: boolean };
  isActive: boolean;
  onClick: () => void;
}

export default function ContactCard({ contact, isActive, onClick }: ContactCardProps) {
  return (
    <div
      onClick={onClick}
      style={{
        padding: '10px 12px',
        cursor: 'pointer',
        background: isActive ? '#e6f4ff' : undefined,
        borderBottom: '1px solid #f0f0f0',
        borderLeft: isActive ? '3px solid #1677ff' : '3px solid transparent',
        transition: 'background 0.2s',
        width: '100%',
        boxSizing: 'border-box',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
        <Space size={4}>
          {contact.channelTypes?.map((ch) => (
            <span key={ch} style={{ fontSize: 14 }}>
              {channelIcons[ch] ?? <MessageOutlined />}
            </span>
          ))}
        </Space>
        <Text strong ellipsis style={{ flex: 1 }}>
          {contactDisplayName(contact)}
        </Text>
        <Space size={4}>
          {contact.pinned ? (
            <Tooltip title="已置顶">
              <PushpinFilled aria-label="已置顶" style={{ color: '#1677ff', fontSize: 12 }} />
            </Tooltip>
          ) : null}
          {contact.unreadCount > 0 && (
            <Badge count={contact.unreadCount} size="small" />
          )}
          {contact.messageCount > 0 && (
            <Text type="secondary" style={{ fontSize: 11, whiteSpace: 'nowrap' }}>
              {formatTime(contact.lastMessageAt)}
            </Text>
          )}
        </Space>
      </div>
      {contact.lastText && (
        <Text
          type="secondary"
          ellipsis
          style={{ fontSize: 12, paddingLeft: 24 }}
        >
          {contact.lastText}
        </Text>
      )}
      <div style={{ paddingLeft: 24, marginTop: 2 }}>
        {contact.channelTypes?.map((ch) => (
          <Tag key={ch} style={{ fontSize: 10, lineHeight: '16px' }}>
            {ch}
          </Tag>
        ))}
      </div>
    </div>
  );
}
