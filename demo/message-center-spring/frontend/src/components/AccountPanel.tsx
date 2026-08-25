import { UserOutlined } from '@ant-design/icons';
import { Divider, Flex, Typography } from 'antd';
import { WeComBindingPanel } from './wecom/WeComBindingPanel';

const { Text, Title } = Typography;

export function AccountPanel({ username }: { username: string | null }) {
  return (
    <Flex vertical gap={4}>
      <Flex align="center" gap={10}>
        <UserOutlined />
        <Text strong>{username || '当前账号'}</Text>
      </Flex>
      <Divider style={{ margin: '16px 0' }} />
      <Title level={5} style={{ marginTop: 0 }}>企业微信</Title>
      <WeComBindingPanel />
    </Flex>
  );
}
