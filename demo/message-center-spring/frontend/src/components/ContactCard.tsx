import { Badge, Typography, Tag, Tooltip } from 'antd';
import { PushpinFilled } from '@ant-design/icons';
import type { ContactResponse } from '../api/types';
import { contactDisplayName } from '../utils/contactDisplayName';

const { Text } = Typography;

const AVATAR_COLORS = ['blue', 'green', 'indigo', 'amber', 'red', 'teal', 'slate'] as const;
const CHANNEL_MARKERS: Record<string, string> = {
  email: 'mail',
  chatapp: 'wa',
  wecom: 'wecom',
  phone: 'phone',
  call: 'phone',
};

function stableAvatarColor(id: string): typeof AVATAR_COLORS[number] {
  let hash = 0;
  for (const character of id) hash = (hash * 31 + character.charCodeAt(0)) | 0;
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
}

function contactInitial(name: string): string {
  return Array.from(name.trim())[0] ?? '?';
}

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
  contact: Pick<ContactResponse, 'id' | 'displayName' | 'channelTypes' | 'lastMessageAt' | 'lastText' | 'messageCount' | 'unreadCount' | 'matchedTags'> & { remark?: string | null; pinned?: boolean };
  isActive: boolean;
  onClick: () => void;
}

export default function ContactCard({ contact, isActive, onClick }: ContactCardProps) {
  const displayName = contactDisplayName(contact);
  const avatarColor = stableAvatarColor(contact.id);
  const channelTypes = contact.channelTypes ?? [];
  return (
    <div
      className={`conversation-contact ${isActive ? 'is-active' : ''}`}
      onClick={onClick}
      style={{
        padding: '8px 10px',
        cursor: 'pointer',
        transition: 'background 0.2s',
        width: '100%',
        boxSizing: 'border-box',
      }}
    >
      <div className={`contact-avatar contact-avatar-color-${avatarColor}`} data-testid="contact-avatar">
        {contactInitial(displayName)}
        {channelTypes.slice(0, 2).map((channel, index) => (
          <i
            key={`${channel}-${index}`}
            className={`contact-channel-dot contact-channel-dot-${CHANNEL_MARKERS[channel] ?? 'other'} ${index === 0 ? 'is-top' : 'is-bottom'}`}
            data-channel={channel}
            aria-label={`${channel} 渠道`}
          />
        ))}
      </div>
      <div className="contact-copy">
        <div className="contact-heading">
          <Text strong ellipsis className="contact-name">{displayName}</Text>
          {contact.messageCount > 0 && <Text type="secondary" className="contact-time">{formatTime(contact.lastMessageAt)}</Text>}
        </div>
        {contact.lastText && <div className="contact-preview">{contact.lastText}</div>}
        {contact.matchedTags && contact.matchedTags.length > 0 && (
          <div className="contact-tags">
            {contact.matchedTags.map((name) => <Tag key={name} color="blue">{name}</Tag>)}
          </div>
        )}
      </div>
      <div className="contact-actions">
        {contact.pinned ? <Tooltip title="已置顶"><PushpinFilled aria-label="已置顶" /></Tooltip> : null}
        {contact.unreadCount > 0 && <Badge count={contact.unreadCount} size="small" />}
      </div>
    </div>
  );
}
