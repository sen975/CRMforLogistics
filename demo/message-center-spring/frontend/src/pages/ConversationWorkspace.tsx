import { Empty, Typography } from 'antd';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import ThreadPage from './ThreadPage';
import { fetchWeComGroupThread } from '../api/endpoints';
import { WeComGroupHeader } from '../components/wecom/WeComGroupHeader';
import { WeComGroupParticipants } from '../components/wecom/WeComGroupParticipants';

const { Text } = Typography;

/** Canonical workspace entry. Contact rendering remains the existing mixed timeline until Task 5. */
export default function ConversationWorkspace() {
  const { contactId, sourceConversationId } = useParams();
  const group = useQuery({
    queryKey: ['wecom-group-thread', sourceConversationId],
    queryFn: () => fetchWeComGroupThread(sourceConversationId!),
    enabled: !!sourceConversationId,
  });
  if (contactId) return <ThreadPage />;
  if (group.isLoading) return <div style={{ padding: 24 }}>企业微信群加载中…</div>;
  if (group.data) {
    return (
      <div data-testid="wecom-group-workspace" style={{ height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <WeComGroupHeader thread={group.data} />
        <div style={{ padding: '0 12px', maxHeight: 180, overflow: 'auto' }}><WeComGroupParticipants participants={group.data.participants} /></div>
        <div style={{ flex: 1, minHeight: 0, display: 'grid', placeItems: 'center' }}><Empty description="企业微信消息展示将在会话组件中加载" /></div>
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
