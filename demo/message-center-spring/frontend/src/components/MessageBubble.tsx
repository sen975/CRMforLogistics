import { Typography, Tag, Space } from 'antd';
import { MailOutlined, MessageOutlined, WechatOutlined, PhoneOutlined, PictureOutlined, VideoCameraOutlined, FileOutlined } from '@ant-design/icons';
import type { MessageResponse } from '../api/types';
import MessageMedia from './MessageMedia';
import { decodeHtmlEntities } from '../utils/htmlEntities';

const { Text } = Typography;

const channelIcons: Record<string, React.ReactNode> = {
  email: <MailOutlined />,
  chatapp: <MessageOutlined />,
  wecom: <WechatOutlined />,
  call: <PhoneOutlined />,
};

const kindIcons: Record<string, React.ReactNode> = {
  image: <PictureOutlined />,
  video: <VideoCameraOutlined />,
  document: <FileOutlined />,
};

const outboundStatuses: Record<string, { label: string; color: string }> = {
  pending: { label: '排队中', color: 'gold' },
  processing: { label: '发送中', color: 'processing' },
  submission_unknown: { label: '状态待确认', color: 'orange' },
  submitted: { label: '已提交', color: 'blue' },
  sent: { label: '已发送', color: 'cyan' },
  delivered: { label: '已送达', color: 'green' },
  read: { label: '已读', color: 'success' },
  failed: { label: '发送失败', color: 'error' },
  cancelled: { label: '已取消', color: 'default' },
};

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

interface MessageBubbleProps {
  message: MessageResponse;
  isActive: boolean;
  onClick: () => void;
}

function renderBody(message: MessageResponse) {
  // Emails: compact — show only subject in bubble, full body in detail panel
  if (message.kind === 'email' || message.channelType === 'email') {
    if (message.bodyHtml) {
      return (
        <div
          style={{ fontSize: 13, lineHeight: 1.5 }}
          dangerouslySetInnerHTML={{ __html: message.bodyHtml }}
        />
      );
    }
    if (message.bodyText) {
      const plainText = decodeHtmlEntities(message.bodyText);
      return (
        <Text type="secondary" style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>
          {plainText.length > 120 ? plainText.slice(0, 120) + '...' : plainText}
        </Text>
      );
    }
    return null;
  }

  // Non-email messages use the normalized bodyText projection.
  const detected = detectContent(message);

  if (detected.type === 'image') {
    const attachment = message.attachments?.find((item) => item.mediaKind === 'image');
    if (attachment) {
      return <MessageMedia attachment={attachment} />;
    }
    return (
      <div style={{ fontSize: 13 }}>
        <Space>
          <PictureOutlined />
          <Text type="secondary">
            {detected.fileName || '图片'}
            {detected.fileSize && ` (${(Number(detected.fileSize) / 1024).toFixed(1)}KB)`}
          </Text>
        </Space>
      </div>
    );
  }

  if (detected.type === 'video') {
    return (
      <div style={{ fontSize: 13 }}>
        <Space>
          <VideoCameraOutlined />
          <Text type="secondary">
            {detected.fileName || '视频'}
            {detected.fileSize && ` (${(Number(detected.fileSize) / 1024).toFixed(1)}KB)`}
          </Text>
        </Space>
      </div>
    );
  }

  if (detected.type === 'document') {
    return (
      <div style={{ fontSize: 13 }}>
        <Space>
          <FileOutlined />
          <Text type="secondary">
            {detected.fileName || '文件'}
            {detected.fileSize && ` (${(Number(detected.fileSize) / 1024).toFixed(1)}KB)`}
          </Text>
        </Space>
      </div>
    );
  }

  // Plain text
  return (
    <Text style={{ fontSize: 13, whiteSpace: 'pre-wrap' }}>
      {detected.displayText || message.bodyText}
    </Text>
  );
}

interface DetectedContent {
  type: 'text' | 'image' | 'video' | 'document';
  fileName?: string;
  fileSize?: string;
  displayText?: string;
}

function detectContent(message: MessageResponse): DetectedContent {
  const { bodyText } = message;

  // Try JSON parsing for media detection
  if (bodyText) {
    // Quick check: does it look like media JSON?
    if (bodyText.includes('"mediaId"') || bodyText.includes('"mediaType"')
        || bodyText.includes('"fileContentType"')) {
      try {
        const obj = JSON.parse(bodyText);
        if (obj.mediaId || obj.mediaType || obj.fileContentType) {
          const ct = (obj.mediaType || obj.fileContentType || '').toLowerCase();
          let type: DetectedContent['type'] = 'document';
          if (ct.includes('image')) type = 'image';
          else if (ct.includes('video')) type = 'video';
          return { type, fileName: obj.fileName, fileSize: obj.fileSize };
        }
      } catch { /* not JSON */ }
    }
  }

  // For kind-based fallback
  if (message.kind === 'image' || message.kind === 'video' || message.kind === 'document') {
    let type: DetectedContent['type'] = message.kind as DetectedContent['type'];
    let fileName: string | undefined;
    let fileSize: string | undefined;
    if (bodyText) {
      try {
        const obj = JSON.parse(bodyText);
        fileName = obj.fileName;
        fileSize = obj.fileSize;
      } catch { /* use bodyText as-is */ }
    }
    return { type, fileName, fileSize };
  }

  return { type: 'text', displayText: bodyText };
}

export default function MessageBubble({ message, isActive, onClick }: MessageBubbleProps) {
  if (message.channelType === 'wecom') return null;
  const isOutbound = message.direction === 'outbound';

  return (
    <div
      onClick={onClick}
      style={{
        display: 'flex',
        flexDirection: isOutbound ? 'row-reverse' : 'row',
        gap: 8,
        marginBottom: 12,
        alignItems: 'flex-start',
      }}
    >
      <div style={{ fontSize: 18, paddingTop: 4 }}>
        {channelIcons[message.channelType] ?? <MessageOutlined />}
      </div>
      <div
        style={{
          maxWidth: 'min(320px, 80%)',
          padding: '8px 12px',
          borderRadius: 8,
          background: isOutbound ? '#dcf8c6' : isActive ? '#e6f4ff' : '#f0f0f0',
          border: isActive ? '2px solid #1677ff' : '2px solid transparent',
          cursor: 'pointer',
        }}
      >
        {message.subject && (
          <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>
            {message.subject}
          </Text>
        )}
        {renderBody(message)}
        <div style={{ marginTop: 4, display: 'flex', alignItems: 'center', gap: 8 }}>
          <Space size={4}>
            <Tag style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}>
              {message.channelType}
            </Tag>
            {kindIcons[message.kind] && (
              <Tag style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}>
                {kindIcons[message.kind]}
              </Tag>
            )}
            {message.kind === 'call' && (
              <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}>
                通话
              </Tag>
            )}
            {isOutbound && outboundStatuses[message.status] && (
              <Tag
                color={outboundStatuses[message.status].color}
                style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}
              >
                {outboundStatuses[message.status].label}
              </Tag>
            )}
          </Space>
          <Text type="secondary" style={{ fontSize: 11 }}>
            {formatTime(message.occurredAt)}
          </Text>
        </div>
      </div>
    </div>
  );
}
