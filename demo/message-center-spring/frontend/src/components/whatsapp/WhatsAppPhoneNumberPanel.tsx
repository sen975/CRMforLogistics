import { List, Space, Tag, Typography } from 'antd';
import { NumberOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { fetchWhatsAppPhoneNumbers } from '../../api/endpoints';
import type { WhatsAppPhoneNumberStatus } from '../../api/types';

const { Text } = Typography;

export function WhatsAppPhoneNumberPanel() {
  const { data = [] } = useQuery({
    queryKey: ['whatsappPhoneNumbers'],
    queryFn: fetchWhatsAppPhoneNumbers,
    retry: false,
  });

  return <List
    size="small"
    dataSource={data}
    locale={{ emptyText: '尚未绑定 WhatsApp 号码' }}
    renderItem={(item: WhatsAppPhoneNumberStatus) => <List.Item>
      <Space direction="vertical" size={0}>
        <Space>
          <NumberOutlined />
          <Text strong>尾号 {item.phoneNumberLast4}</Text>
          <Tag color={item.providerPhoneStatus === 'ACTIVE' ? 'green' : 'orange'}>
            {item.providerPhoneStatus ?? '未知'}
          </Tag>
        </Space>
        <Text type="secondary">
          {item.onboardingMode === 'BUSINESS_APP_COEXISTENCE' ? 'Business App 共存' : 'Business API'}
          {' · '}{item.phoneVerificationStatus ?? '未验证'}
        </Text>
      </Space>
    </List.Item>}
  />;
}
