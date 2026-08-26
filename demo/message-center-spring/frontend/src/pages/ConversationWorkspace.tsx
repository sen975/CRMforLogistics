import { Empty, Typography } from 'antd';
import { useParams } from 'react-router-dom';
import ThreadPage from './ThreadPage';

const { Text } = Typography;

/** Canonical workspace entry. Contact rendering remains the existing mixed timeline until Task 5. */
export default function ConversationWorkspace() {
  const { contactId, sourceConversationId } = useParams();
  if (contactId) return <ThreadPage />;
  return (
    <div style={{ height: '100%', display: 'grid', placeItems: 'center' }}>
      <Empty description={<Text>选择左侧企业微信群查看会话</Text>} />
      <span data-testid="wecom-group-route" hidden>{sourceConversationId}</span>
    </div>
  );
}
