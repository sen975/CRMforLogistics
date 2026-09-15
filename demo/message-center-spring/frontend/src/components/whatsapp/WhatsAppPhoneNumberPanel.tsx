import { List, Space, Tag, Typography } from 'antd';
import { NumberOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { fetchWhatsAppPhoneOperations } from '../../api/endpoints';
import type { WhatsAppPhoneOperationProjection } from '../../api/types';

const { Text } = Typography;

export function WhatsAppPhoneNumberPanel() {
  const { data = [] } = useQuery({
    queryKey: ['whatsappPhoneOperations'],
    queryFn: fetchWhatsAppPhoneOperations,
    retry: false,
  });

  return <List
    size="small"
    dataSource={data}
    locale={{ emptyText: '尚未绑定 WhatsApp 号码' }}
    renderItem={(item: WhatsAppPhoneOperationProjection) => <List.Item>
      <Space direction="vertical" size={0}>
        <Space>
          <NumberOutlined />
          <Text strong>尾号 {item.phoneNumberLast4}</Text>
          <Tag color={item.status === 'REGISTERED' ? 'green' : 'orange'}>{item.status}</Tag>
        </Space>
        <Text type="secondary">
          {item.accountName || '企业 API 新号码'}{' · '}{item.accountRemark || '无备注'}
        </Text>
      </Space>
    </List.Item>}
  />;
}
