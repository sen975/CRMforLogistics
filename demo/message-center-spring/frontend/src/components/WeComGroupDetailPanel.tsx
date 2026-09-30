import { Button, Tabs, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { fetchWeComGroupThread, refreshWeComGroupName } from '../api/endpoints';
import AiTopicTimeline from './AiTopicTimeline';
import { WeComGroupParticipants } from './wecom/WeComGroupParticipants';
import { useWeComGroupTopicTimeline } from '../hooks/useWeComGroupTopicTimeline';
import { useSse } from '../hooks/useSse';

const { Text } = Typography;

/** Shared right-side detail panel content for a WeCom group route. */
export default function WeComGroupDetailPanel() {
  const { sourceConversationId } = useParams();
  const [activeTab, setActiveTab] = useState('topics');
  const [nameRefreshQueued, setNameRefreshQueued] = useState(false);
  const queryClient = useQueryClient();
  const timeline = useWeComGroupTopicTimeline(sourceConversationId, true);
  const group = useQuery({
    queryKey: ['wecom-group-thread', sourceConversationId],
    queryFn: () => fetchWeComGroupThread(sourceConversationId!),
    enabled: Boolean(sourceConversationId) && activeTab === 'members',
  });
  const nameRefresh = useMutation({
    mutationFn: () => refreshWeComGroupName(sourceConversationId!),
    onMutate: () => setNameRefreshQueued(true),
    onError: () => setNameRefreshQueued(false),
  });
  useEffect(() => {
    setNameRefreshQueued(false);
  }, [sourceConversationId]);
  useSse((event) => {
    const data = event?.data as { sourceConversationId?: string } | undefined;
    if (data?.sourceConversationId !== sourceConversationId) return;
    setNameRefreshQueued(false);
    void queryClient.invalidateQueries({ queryKey: ['wecom-group-thread', sourceConversationId] });
  }, ['wecom-group-name-refresh-completed']);

  if (!sourceConversationId) {
    return <div style={{ padding: 16 }}><Text type="secondary">选择企业微信群查看 Topic</Text></div>;
  }

  return (
    <div className="mc-detail-panel" style={{ width: '100%', height: '100%', minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <Tabs
        className="wecom-group-detail-tabs"
        activeKey={activeTab}
        onChange={setActiveTab}
        size="small"
        destroyOnHidden
        data-testid="wecom-group-detail-tabs"
        style={{ width: '100%', height: '100%', minWidth: 0, minHeight: 0 }}
        tabBarStyle={{ margin: 0, paddingInline: 8, flex: '0 0 auto' }}
        items={[
        {
          key: 'topics',
          label: 'Topic 时间轴',
          children: <AiTopicTimeline groupMode timeline={timeline.data} actions={{ store: timeline.store, retry: timeline.retry }} />,
        },
        {
          key: 'members',
          label: '群成员',
          children: (
            <div className="cd-body" style={{ padding: '0 16px 16px' }}>
              <div className="cd-toolbar" style={{ justifyContent: 'flex-end', marginBottom: 8 }}>
                <Button
                  type="text"
                  size="small"
                  aria-label="刷新群昵称"
                  icon={<ReloadOutlined />}
                  loading={nameRefresh.isPending || nameRefreshQueued}
                  disabled={nameRefresh.isPending || nameRefreshQueued}
                  onClick={() => nameRefresh.mutate()}
                />
              </div>
              {group.isLoading
                ? <Text type="secondary">群成员加载中…</Text>
                : <WeComGroupParticipants participants={group.data?.participants ?? []} />}
            </div>
          ),
        },
        ]}
      />
    </div>
  );
}
