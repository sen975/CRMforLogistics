import { useMemo, useState } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { Alert, Avatar, Button, Drawer, List, Spin, Typography } from 'antd';
import { fetchWeComBinding, getWeComExternalContact, listWeComExternalContacts } from '../../api/endpoints';
import WeComProviderView from './WeComProviderView';
import { externalContactIds } from './wecomProviderData';
import { wecomAvatarColor, wecomAvatarLetter } from './wecomAvatar';

const { Text } = Typography;

type ExternalContactProfile = {
  name: string;
  avatar?: string;
};

function contactProfile(data: Record<string, unknown> | undefined): ExternalContactProfile {
  const raw = data?.external_contact ?? data?.externalContact ?? data;
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return { name: '未获取昵称' };
  }
  const record = raw as Record<string, unknown>;
  const name = [record.remark, record.name, record.nickname, record.alias]
    .find((value): value is string => typeof value === 'string' && value.trim().length > 0)
    ?.trim() || '未获取昵称';
  const avatar = typeof record.avatar === 'string' && record.avatar.trim() ? record.avatar.trim() : undefined;
  return { name, avatar };
}

export default function WeComExternalContactPanel({ authCorpId }: { authCorpId: string }) {
  const [externalUserId, setExternalUserId] = useState('');
  const binding = useQuery({ queryKey: ['wecom', 'binding'], queryFn: fetchWeComBinding, retry: false });
  const boundToSelectedCorp = binding.data?.bound === true && binding.data.authCorpId === authCorpId;
  const contacts = useQuery({
    queryKey: ['wecom', 'external-contacts', authCorpId, 'bound-user'],
    queryFn: () => listWeComExternalContacts(authCorpId),
    enabled: boundToSelectedCorp,
    retry: false,
  });
  const contactIds = useMemo(() => externalContactIds(contacts.data), [contacts.data]);
  const customerDetails = useQueries({
    queries: contactIds.slice(0, 100).map((externalId) => ({
      queryKey: ['wecom', 'external-contact', authCorpId, externalId],
      queryFn: () => getWeComExternalContact(authCorpId, externalId),
      enabled: boundToSelectedCorp,
      staleTime: 5 * 60 * 1000,
      retry: false,
    })),
  });
  const detailQueriesById = useMemo(
    () => new Map(contactIds.slice(0, 100).map((externalId, index) => [externalId, customerDetails[index]?.data])),
    [contactIds, customerDetails],
  );
  const detailStatesById = useMemo(
    () => new Map(contactIds.slice(0, 100).map((externalId, index) => [externalId, customerDetails[index]])),
    [contactIds, customerDetails],
  );
  const detail = useQuery({
    queryKey: ['wecom', 'external-contact', authCorpId, externalUserId],
    queryFn: () => getWeComExternalContact(authCorpId, externalUserId),
    enabled: !!authCorpId && !!externalUserId,
    retry: false,
  });
  return (
    <section className="wecom-panel" aria-label="客户联系管理">
      {binding.isLoading ? <Spin /> : !binding.data?.bound ? (
        <Alert type="warning" showIcon message="当前账号尚未绑定企业微信" />
      ) : !boundToSelectedCorp ? (
        <Alert type="warning" showIcon message="当前账号绑定的企业微信企业与当前选择不一致" />
      ) : null}
      <Spin spinning={contacts.isFetching}>
        {boundToSelectedCorp && contactIds.length > 0 && (
          <List
            size="small"
            header={<Text strong>外部联系人 ({contactIds.length})</Text>}
            dataSource={contactIds}
            renderItem={(externalId) => (
              <List.Item
                actions={[<Button key="detail" type="link" size="small" onClick={() => setExternalUserId(externalId)}>查看详情</Button>]}
              >
                {(() => {
                  const queryData = detailQueriesById.get(externalId);
                  const queryState = detailStatesById.get(externalId);
                  const profile = contactProfile(queryData);
                  return (
                    <List.Item.Meta
                      avatar={(
                        <Avatar
                          src={profile.avatar}
                          style={profile.avatar ? undefined : { backgroundColor: wecomAvatarColor('EXTERNAL_CONTACT') }}
                        >
                          {wecomAvatarLetter(profile.name)}
                        </Avatar>
                      )}
                      title={profile.name}
                      description={queryState?.isFetching ? '正在获取客户资料' : undefined}
                    />
                  );
                })()}
              </List.Item>
            )}
          />
        )}
        {boundToSelectedCorp && contactIds.length === 0 && <WeComProviderView data={contacts.data} emptyText="当前绑定成员暂无客户" />}
      </Spin>
      <Drawer title="客户详情" open={!!externalUserId} onClose={() => setExternalUserId('')} width="min(560px, 100vw)">
        <Spin spinning={detail.isFetching}><WeComProviderView data={detail.data} /></Spin>
      </Drawer>
    </section>
  );
}
