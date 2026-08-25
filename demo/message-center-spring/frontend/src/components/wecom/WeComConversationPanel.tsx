import { ReloadOutlined } from '@ant-design/icons';
import { Button, Empty, Flex, Typography, theme } from 'antd';
import { useState } from 'react';
import type { MessageResponse } from '../../api/types';
import type { WeComViewerHandle } from '../../hooks/useWeComViewer';
import { WeComConversationFrame } from './WeComConversationFrame';

const { Text } = Typography;

export function WeComConversationPanel({
  contactPointId,
  items,
  viewer,
}: {
  contactPointId: string;
  items: MessageResponse[];
  viewer: WeComViewerHandle;
}) {
  const { token } = theme.useToken();
  const [reloadKey, setReloadKey] = useState(0);
  if (!contactPointId) return <Empty description="联系人缺少企业微信身份" />;
  const identityValue = contactPointId.startsWith('wecom:')
    ? contactPointId.slice('wecom:'.length)
    : contactPointId;
  const openClientUrl = `wxwork://message?username=${encodeURIComponent(identityValue)}`;
  const handleOpenClient = () => {
    void navigator.clipboard?.writeText(identityValue);
  };

  return (
    <div
      data-testid="wecom-conversation-page"
      style={{ flex: '1 1 auto', width: '100%', height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column' }}
    >
      <Flex align="center" justify="space-between" style={{ minHeight: 40, padding: '0 12px', borderBottom: `1px solid ${token.colorBorderSecondary}` }}>
        <Text type="secondary">企业微信会话</Text>
        <Button type="text" size="small" aria-label="刷新企业微信会话" icon={<ReloadOutlined />} onClick={() => setReloadKey((value) => value + 1)} />
      </Flex>
      <div
        style={{
          flex: '1 1 auto',
          minHeight: 0,
          minWidth: 0,
          overflow: 'hidden',
          padding: 0,
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        {items.length > 0 ? (
          <WeComConversationFrame
            contactPointId={contactPointId}
            items={items}
            viewer={viewer}
            reloadKey={reloadKey}
          />
        ) : <Empty description="暂无企业微信消息" />}
      </div>
      <Flex
        align="center"
        justify="space-between"
        gap={12}
        style={{ borderTop: `1px solid ${token.colorBorderSecondary}`, padding: '8px 12px' }}
      >
        <a
          role="button"
          className="ant-btn ant-btn-primary ant-btn-sm"
          href={openClientUrl}
          data-testid="wecom-open-client-link"
          aria-label="在企业微信中打开"
          onClick={handleOpenClient}
        >
          在企业微信中打开
        </a>
      </Flex>
    </div>
  );
}
