import { Empty, Typography } from 'antd';
import { useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import ThreadPage from './ThreadPage';
import { fetchWeComGroupThread } from '../api/endpoints';
import { WeComConversationPanel } from '../components/wecom/WeComConversationPanel';
import { useWeComViewer } from '../hooks/useWeComViewer';
import { useSse } from '../hooks/useSse';

const { Text } = Typography;

/** Canonical workspace entry. Contact rendering remains the existing mixed timeline until Task 5. */
export default function ConversationWorkspace() {
  const { contactId, sourceConversationId } = useParams();
  const queryClient = useQueryClient();
  const group = useQuery({
    queryKey: ['wecom-group-thread', sourceConversationId],
    queryFn: () => fetchWeComGroupThread(sourceConversationId!),
    enabled: !!sourceConversationId,
  });
  const viewer = useWeComViewer();
  useSse((event) => {
    if (event?.type === 'wecom-group-kind-sync-completed') {
      void queryClient.invalidateQueries({ queryKey: ['wecom-group-thread', sourceConversationId] });
      return;
    }
    const data = event?.data as { sourceConversationId?: string } | undefined;
    if (data?.sourceConversationId === sourceConversationId) {
      void queryClient.invalidateQueries({ queryKey: ['wecom-group-thread', sourceConversationId] });
    }
  }, ['wecom-group-name-refresh-completed', 'wecom-group-kind-sync-completed']);
  if (contactId) return <ThreadPage />;
  if (group.isLoading) return <div style={{ padding: 24 }}>企业微信群加载中…</div>;
  if (group.data) {
    return (
      <div data-testid="wecom-group-workspace" style={{ height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
          <WeComConversationPanel
            contactPointId={`wecom:${group.data.groupChatId || group.data.sourceConversationId}`}
            target={{ targetType: 'WECOM_GROUP', targetId: group.data.sourceConversationId, chatId: group.data.groupChatId }}
            items={group.data.items}
            viewer={viewer}
            openClientUrl={group.data.openClientUrl}
          />
        </div>
      </div>
    );
  }
  return (
    <div style={{ height: '100%', display: 'grid', placeItems: 'center' }}>
      <Empty description={<Text>选择左侧企业微信群查看会话</Text>} />
      <span data-testid="wecom-group-route" hidden>{sourceConversationId}</span>
    </div>
  );
}
