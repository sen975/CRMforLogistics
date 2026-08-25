import { ReloadOutlined } from '@ant-design/icons';
import { Button, Empty, Flex, Typography, theme } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import type { MessageResponse } from '../../api/types';
import type { WeComViewerHandle } from '../../hooks/useWeComViewer';
import { WeComConversationFrame } from './WeComConversationFrame';
import { WeComConversationSelector, type WeComConversationOption } from './WeComConversationSelector';

const { Text } = Typography;

export function WeComConversationPanel({
  contactPointId,
  items,
  viewer,
  conversations,
}: {
  contactPointId: string;
  items: MessageResponse[];
  viewer: WeComViewerHandle;
  conversations?: Array<WeComConversationOption & { contactPointId: string; items: MessageResponse[] }>;
}) {
  const { token } = theme.useToken();
  const [reloadKey, setReloadKey] = useState(0);
  const options = useMemo(() => conversations ?? [], [conversations]);
  const [selectedConversationId, setSelectedConversationId] = useState(options[0]?.id ?? '');
  useEffect(() => {
    if (!options.some((option) => option.id === selectedConversationId)) {
      setSelectedConversationId(options[0]?.id ?? '');
    }
  }, [options, selectedConversationId]);
  const effectiveConversationId = options.some((option) => option.id === selectedConversationId)
    ? selectedConversationId
    : options[0]?.id ?? '';
  const selectedConversation = options.find((option) => option.id === effectiveConversationId);
  const activeContactPointId = selectedConversation?.contactPointId ?? contactPointId;
  const activeItems = selectedConversation?.items ?? items;
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
      <WeComConversationSelector
        options={options}
        selectedId={effectiveConversationId}
        onSelect={(id) => {
          setSelectedConversationId(id);
          setReloadKey((value) => value + 1);
        }}
      />
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
        {activeItems.length > 0 ? (
          <WeComConversationFrame
            contactPointId={activeContactPointId}
            items={activeItems}
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
