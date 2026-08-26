import { Avatar, List, Tag, Typography } from 'antd';
import { UserOutlined, RobotOutlined } from '@ant-design/icons';
import type { WeComPartyView } from '../../api/types';

const { Text } = Typography;

export function WeComGroupParticipants({ participants }: { participants: WeComPartyView[] }) {
  return (
    <List
      size="small"
      dataSource={participants}
      locale={{ emptyText: '暂无可见群成员' }}
      renderItem={(party) => {
        const canOpen = party.contactAccessible && !!party.contactId;
        const content = (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, width: '100%' }}>
            <Avatar size="small" src={party.avatarUrl || undefined} icon={party.partyType === 'ROBOT' ? <RobotOutlined /> : <UserOutlined />} />
            <Text ellipsis style={{ flex: 1 }}>{party.displayName || party.providerPartyId}</Text>
            <Tag style={{ margin: 0 }}>{party.partyType}</Tag>
          </div>
        );
        return <List.Item>{canOpen ? <a href={`/conversations/contact/${party.contactId}`}>{content}</a> : content}</List.Item>;
      }}
    />
  );
}
