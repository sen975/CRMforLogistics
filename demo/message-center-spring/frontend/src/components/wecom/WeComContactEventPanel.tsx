import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Empty, List, Spin, Tag, Typography } from 'antd';
import { fetchWeComBinding, listWeComContactEvents } from '../../api/endpoints';
import type { WeComContactEvent } from '../../api/types';

const { Text } = Typography;

/** 变化类型的中文标签。未知值按原样展示 —— 不猜、不合并成「其他」。 */
const CHANGE_TYPE_LABELS: Record<string, string> = {
  add_external_contact: '新增客户',
  edit_external_contact: '编辑客户',
  add_half_external_contact: '免验证添加',
  del_external_contact: '删除客户',
  del_follow_user: '客户删除了成员',
  transfer_fail: '接替失败',
};

const FAIL_REASON_LABELS: Record<string, string> = {
  customer_refused: '客户拒绝',
  customer_limit_exceed: '接替成员客户数已达上限',
};

const PAGE_SIZE = 50;
const MAX_PAGE_SIZE = 200;

/**
 * 「客户动态」：客户关系变化的**流水**，与「客户联系」面板的**快照**语义并列。
 *
 * <p>快照回答「现在有哪些客户」；只有流水能回答「这个客户是什么时候、由谁、经哪个渠道加进来的」
 * 以及「什么时候流失的」。企微不提供历史事件查询，所以数据自接入之日起，**不含接入前的历史**，
 * 这一点必须在空态里讲清楚，否则用户会把它当成全量历史。
 */
export default function WeComContactEventPanel({ authCorpId }: { authCorpId: string }) {
  const [limit, setLimit] = useState(PAGE_SIZE);
  const binding = useQuery({ queryKey: ['wecom', 'binding'], queryFn: fetchWeComBinding, retry: false });
  const boundToSelectedCorp = binding.data?.bound === true && binding.data.authCorpId === authCorpId;
  const events = useQuery({
    queryKey: ['wecom', 'contact-events', authCorpId, limit],
    queryFn: () => listWeComContactEvents(authCorpId, { limit }),
    enabled: boundToSelectedCorp,
    retry: false,
  });
  const rows = events.data ?? [];

  return (
    <section className="wecom-panel" aria-label="客户动态">
      {binding.isLoading ? <Spin /> : !binding.data?.bound ? (
        <Alert type="warning" showIcon message="当前账号尚未绑定企业微信" />
      ) : !boundToSelectedCorp ? (
        <Alert type="warning" showIcon message="当前账号绑定的企业微信企业与当前选择不一致" />
      ) : null}
      {boundToSelectedCorp && events.isError && (
        <Alert
          type="error"
          showIcon
          message="客户动态加载失败"
          description="事件流水读取失败，请稍后重试。已落库的事件不会丢失。"
        />
      )}
      <Spin spinning={events.isFetching}>
        {boundToSelectedCorp && rows.length > 0 && (
          <List
            size="small"
            dataSource={rows}
            header={<Text strong>客户动态 ({rows.length})</Text>}
            renderItem={(event) => <ContactEventItem event={event} />}
            footer={rows.length >= limit && limit < MAX_PAGE_SIZE ? (
              <Button type="link" size="small" onClick={() => setLimit((current) => Math.min(current + PAGE_SIZE, MAX_PAGE_SIZE))}>
                加载更多
              </Button>
            ) : undefined}
          />
        )}
        {boundToSelectedCorp && !events.isFetching && !events.isError && rows.length === 0 && (
          <Empty
            description={(
              <span>
                自接入日起无客户关系变化
                <br />
                <Text type="secondary">企业微信不提供历史事件查询，接入之前的变化不会出现在这里</Text>
              </span>
            )}
          />
        )}
      </Spin>
    </section>
  );
}

function ContactEventItem({ event }: { event: WeComContactEvent }) {
  const label = CHANGE_TYPE_LABELS[event.changeType] ?? event.changeType;
  const occurredAt = formatOccurredAt(event.providerCreatedAt);
  return (
    <List.Item>
      <List.Item.Meta
        title={(
          <span>
            <Tag color={eventColor(event.changeType)}>{label}</Tag>
            <Text>{event.externalUserId ?? '未获取昵称'}</Text>
          </span>
        )}
        description={(
          <span>
            {occurredAt}
            {event.wecomUserId ? ` · 成员 ${event.wecomUserId}` : ''}
            {event.state ? ` · 渠道 ${event.state}` : ''}
            {event.failReason ? ` · ${FAIL_REASON_LABELS[event.failReason] ?? event.failReason}` : ''}
            {event.providerSource === 'DELETE_BY_TRANSFER' ? ' · 在职继承自动转接' : ''}
          </span>
        )}
      />
    </List.Item>
  );
}

function eventColor(changeType: string): string {
  if (changeType.startsWith('add')) return 'green';
  if (changeType.startsWith('del')) return 'red';
  return 'blue';
}

function formatOccurredAt(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN');
}
