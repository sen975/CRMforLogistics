import { GroupOutlined, ReloadOutlined } from '@ant-design/icons';
import { Avatar, Button, Empty, Flex, Typography, theme } from 'antd';
import { useState } from 'react';
import type { MessageResponse, RelatedWeComGroupResponse } from '../../api/types';
import type { WeComViewerTarget } from '../../api/types';
import type { WeComViewerHandle } from '../../hooks/useWeComViewer';
import { weComGroupDisplayName } from '../../utils/weComGroupDisplayName';
import { WeComConversationFrame } from './WeComConversationFrame';

const { Text } = Typography;

export function WeComConversationPanel({
  contactPointId,
  items,
  viewer,
  target,
  openClientUrl,
  relatedGroups = [],
  onOpenGroup,
  onSwitchToMixed,
}: {
  contactPointId: string;
  items: MessageResponse[];
  viewer: WeComViewerHandle;
  target?: WeComViewerTarget;
  openClientUrl?: string | null;
  relatedGroups?: RelatedWeComGroupResponse[];
  onOpenGroup?: (sourceConversationId: string) => void;
  onSwitchToMixed?: () => void;
}) {
  const { token } = theme.useToken();
  const [reloadKey, setReloadKey] = useState(0);
  if (!contactPointId) return <Empty description="联系人缺少企业微信身份" />;
  const effectiveOpenClientUrl = target?.targetType === 'WECOM_GROUP'
    ? openClientUrl
    : 'wxwork://';

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
            target={target}
            items={items}
            viewer={viewer}
            reloadKey={reloadKey}
          />
        ) : <Empty description="暂无企业微信消息" />}
      </div>
      {relatedGroups.length > 0 ? (
        <div
          style={{
            flex: '0 0 auto',
            maxHeight: 144,
            overflowY: 'auto',
            borderTop: `1px solid ${token.colorBorderSecondary}`,
            padding: '8px 12px',
          }}
        >
          <Text type="secondary" style={{ display: 'block', marginBottom: 4, fontSize: 12 }}>
            相关企业微信群
          </Text>
          {relatedGroups.map((group) => (
            <Button
              key={group.sourceConversationId}
              type="text"
              block
              onClick={() => onOpenGroup?.(group.sourceConversationId)}
              style={{ height: 40, paddingInline: 4 }}
            >
              <Flex align="center" gap={8} style={{ width: '100%', minWidth: 0 }}>
                <Avatar size={28} src={group.avatarUrl || undefined} icon={<GroupOutlined />} />
                <Text ellipsis style={{ flex: 1, minWidth: 0, textAlign: 'left' }}>
                  {weComGroupDisplayName(group.displayName)}
                </Text>
                <Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
                  {group.participantCount} 人
                </Text>
              </Flex>
            </Button>
          ))}
        </div>
      ) : null}
      <Flex
        align="center"
        justify="space-between"
        gap={12}
        style={{ borderTop: `1px solid ${token.colorBorderSecondary}`, padding: '8px 12px' }}
      >
        {target?.targetType !== 'WECOM_GROUP' && onSwitchToMixed ? (
          <Button type="link" size="small" onClick={onSwitchToMixed}>
            切换
          </Button>
        ) : <span />}
        {effectiveOpenClientUrl ? <a
          role="button"
          className="ant-btn ant-btn-primary ant-btn-sm"
          href={effectiveOpenClientUrl}
          data-testid="wecom-open-client-link"
          aria-label="在企业微信中打开"
        >
          在企业微信中打开
        </a> : null}
      </Flex>
    </div>
  );
}
