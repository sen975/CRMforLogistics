import { useNavigate } from 'react-router-dom';
import { Card, Typography, Row, Col, Statistic, Button, Space, Spin } from 'antd';
import {
  MessageOutlined,
  SendOutlined,
  FileTextOutlined,
  SettingOutlined,
  TeamOutlined,
  ApiOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { fetchContacts, fetchChannelCapabilities } from '../api/endpoints';

const { Title, Paragraph } = Typography;

export default function HomePage() {
  const navigate = useNavigate();

  const { data: contacts, isLoading: contactsLoading } = useQuery({
    queryKey: ['contacts', undefined, 1, 1],
    queryFn: () => fetchContacts({ page: 1, size: 1 }),
  });

  const { data: channels, isLoading: channelsLoading } = useQuery({
    queryKey: ['channelCapabilities'],
    queryFn: fetchChannelCapabilities,
  });

  const contactCount = contacts?.total ?? 0;
  const activeChannels = channels?.filter((c) => c.authStatus === 'active').length ?? 0;

  return (
    <div style={{ maxWidth: 600, margin: '60px auto', padding: 24 }}>
      <div style={{ textAlign: 'center', marginBottom: 32 }}>
        <Title level={2}>统一消息中心</Title>
        <Paragraph type="secondary">
          管理多渠道客户消息，统一收发，高效协作
        </Paragraph>
      </div>

      <Row gutter={16} style={{ marginBottom: 32 }}>
        <Col span={12}>
          <Card size="small">
            {contactsLoading ? (
              <Spin />
            ) : (
              <Statistic title="联系人总数" value={contactCount} prefix={<TeamOutlined />} />
            )}
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            {channelsLoading ? (
              <Spin />
            ) : (
              <Statistic title="活跃渠道" value={activeChannels} prefix={<ApiOutlined />} />
            )}
          </Card>
        </Col>
      </Row>

      <Space size="middle" wrap style={{ justifyContent: 'center', width: '100%' }}>
        <Button
          type="primary"
          icon={<MessageOutlined />}
          size="large"
          onClick={() => {
            if (contactCount > 0 && contacts?.records?.[0]) {
              navigate(`/thread/${contacts.records[0].id}`);
            } else {
              navigate('/send');
            }
          }}
        >
          {contactCount > 0 ? '查看消息' : '开始使用'}
        </Button>
        <Button icon={<SendOutlined />} size="large" onClick={() => navigate('/send')}>
          发送消息
        </Button>
        <Button icon={<FileTextOutlined />} size="large" onClick={() => navigate('/templates')}>
          消息模板
        </Button>
        <Button icon={<SettingOutlined />} size="large" onClick={() => navigate('/settings/channels')}>
          渠道设置
        </Button>
      </Space>
    </div>
  );
}
