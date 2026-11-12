import { useState } from 'react';
import { Alert, Button, List, Popconfirm, Space, Tag, Typography } from 'antd';
import { HistoryOutlined, StopOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchWhatsAppAccounts, requestWhatsAppHistorySync, unlinkWhatsAppAccount } from '../../api/endpoints';
import type { WhatsAppAccountProjection } from '../../api/types';

const { Text } = Typography;

export function WhatsAppAccountPanel() {
  const client = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const accounts = useQuery({ queryKey: ['whatsappAccounts'], queryFn: fetchWhatsAppAccounts, retry: false });
  const refresh = () => void client.invalidateQueries({ queryKey: ['whatsappAccounts'] });
  const unlink = useMutation({ mutationFn: ({ id, reason }: { id: string; reason: string }) => unlinkWhatsAppAccount(id, reason), onSuccess: refresh, onError: () => setError('解绑失败') });
  const history = useMutation({ mutationFn: requestWhatsAppHistorySync, onSuccess: refresh, onError: () => setError('历史同步任务创建失败') });
  return <Space direction="vertical" style={{ width: '100%' }}>
    {error && <Alert type="error" showIcon message={error} />}
    <List size="small" loading={accounts.isLoading} locale={{ emptyText: '暂无 WhatsApp 账号' }} dataSource={accounts.data ?? []}
      renderItem={(item: WhatsAppAccountProjection) => <List.Item actions={[
        item.mode === 'BUSINESS_APP_COEXISTENCE' ? <Button key="history" size="small" icon={<HistoryOutlined />} loading={history.isPending} onClick={() => history.mutate(item.accountId)}>历史同步</Button> : null,
        <Popconfirm key="unlink" title="解绑 WhatsApp 账号" description="仅解除 CRM 关系，不删除 CAMS 资源。" okText="解绑" cancelText="取消" onConfirm={() => unlink.mutate({ id: item.accountId, reason: '用户主动解绑' })}><Button size="small" danger icon={<StopOutlined />}>解绑</Button></Popconfirm>,
      ].filter(Boolean)}>
        <List.Item.Meta title={<Space><Text strong>{item.name}</Text><Tag>{item.mode === 'BUSINESS_APP_COEXISTENCE' ? 'Business App 共存' : '企业 API'}</Tag></Space>} description={<Space><Text type="secondary">{item.maskedPhone}</Text><Text type="secondary">{item.remark || '无备注'}</Text><Tag color={item.providerStatus === 'ACTIVE' ? 'green' : 'orange'}>{item.providerStatus || '未知'}</Tag></Space>} />
      </List.Item>}
    />
  </Space>;
}
