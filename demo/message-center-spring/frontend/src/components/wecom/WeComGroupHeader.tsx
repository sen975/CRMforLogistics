import { Avatar, Button, Flex, Typography } from 'antd';
import { ReloadOutlined, TeamOutlined } from '@ant-design/icons';
import { Tooltip } from 'antd';
import type { WeComGroupThreadResponse } from '../../api/types';
import { weComGroupDisplayName } from '../../utils/weComGroupDisplayName';

const { Text, Title } = Typography;

export function WeComGroupHeader({
  thread,
  onRefreshName,
  nameRefreshPending = false,
}: {
  thread: Pick<WeComGroupThreadResponse, 'displayName' | 'avatarUrl' | 'participants' | 'openClientUrl'>;
  onRefreshName?: () => void;
  nameRefreshPending?: boolean;
}) {
  return (
    <Flex align="center" justify="space-between" gap={12} style={{ padding: '10px 12px', borderBottom: '1px solid #f0f0f0' }}>
      <Flex align="center" gap={8} style={{ minWidth: 0 }}>
        <Avatar src={thread.avatarUrl || undefined} icon={<TeamOutlined />} />
        <div style={{ minWidth: 0 }}>
          <Title level={5} ellipsis style={{ margin: 0 }}>{weComGroupDisplayName(thread.displayName)}</Title>
          <Text type="secondary" style={{ fontSize: 12 }}>{thread.participants.length} 位成员</Text>
        </div>
      </Flex>
      <Flex align="center" gap={4}>
        <Tooltip title={nameRefreshPending ? '群昵称刷新已提交' : '刷新群昵称'}>
          <Button type="text" size="small" icon={<ReloadOutlined spin={nameRefreshPending} />}
            aria-label="刷新群昵称" onClick={onRefreshName} loading={nameRefreshPending}
            disabled={nameRefreshPending} />
        </Tooltip>
        {thread.openClientUrl ? <Button size="small" href={thread.openClientUrl}>在企业微信中打开</Button> : null}
      </Flex>
    </Flex>
  );
}
