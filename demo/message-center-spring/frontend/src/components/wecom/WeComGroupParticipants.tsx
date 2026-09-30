import { Avatar, List, Tag, Typography } from 'antd';
import type { WeComPartyView } from '../../api/types';
import { wecomAvatarColor, wecomAvatarLetter, wecomPartyTypeLabel } from './wecomAvatar';

const { Text } = Typography;

/* 群成员与「渠道身份」同语言（v5 稿 ctx 侧栏）：无边框行 + hover 底，角色退成 chip。
   行的可点性由「能用 contactId 打开联系人」决定 —— 不可打开时退化成纯文本，不假装可点。 */
export function WeComGroupParticipants({ participants }: { participants: WeComPartyView[] }) {
  return (
    <List
      className="cd-participants"
      size="small"
      dataSource={participants}
      locale={{ emptyText: '暂无可见群成员' }}
      renderItem={(party) => {
        const canOpen = party.contactAccessible && !!party.contactId;
        const content = (
          <div className="cd-participant">
            <Avatar
              size="small"
              src={party.avatarUrl || undefined}
              style={party.avatarUrl ? undefined : { backgroundColor: wecomAvatarColor(party.partyType) }}
            >
              {wecomAvatarLetter(party.displayName || '未获取昵称')}
            </Avatar>
            <Text ellipsis className="cd-participant-name">{party.displayName || '未获取昵称'}</Text>
            <Tag className="cd-participant-role">{wecomPartyTypeLabel(party.partyType)}</Tag>
          </div>
        );
        return <List.Item>{canOpen ? <a href={`/conversations/contact/${party.contactId}`}>{content}</a> : content}</List.Item>;
      }}
    />
  );
}
