import { Avatar, Button, Flex, Typography } from 'antd';
import { TeamOutlined } from '@ant-design/icons';
import type { WeComGroupThreadResponse } from '../../api/types';

const { Text, Title } = Typography;

export function WeComGroupHeader({ thread }: { thread: Pick<WeComGroupThreadResponse, 'displayName' | 'avatarUrl' | 'participants' | 'openClientUrl'> }) {
  return (
    <Flex align="center" justify="space-between" gap={12} style={{ padding: '10px 12px', borderBottom: '1px solid #f0f0f0' }}>
      <Flex align="center" gap={8} style={{ minWidth: 0 }}>
        <Avatar src={thread.avatarUrl || undefined} icon={<TeamOutlined />} />
        <div style={{ minWidth: 0 }}>
          <Title level={5} ellipsis style={{ margin: 0 }}>{thread.displayName || '企业微信群'}</Title>
          <Text type="secondary" style={{ fontSize: 12 }}>{thread.participants.length} 位成员</Text>
        </div>
      </Flex>
      {thread.openClientUrl ? <Button size="small" href={thread.openClientUrl}>在企业微信中打开</Button> : null}
    </Flex>
  );
}
