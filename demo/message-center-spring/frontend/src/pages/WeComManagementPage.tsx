import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Empty, Result, Select, Spin, Tabs, Typography } from 'antd';
import { WechatOutlined } from '@ant-design/icons';
import { fetchWeComInstallations } from '../api/endpoints';
import { useAuth } from '../hooks/useAuth';
import WeComAppChatPanel from '../components/wecom/WeComAppChatPanel';
import WeComExternalContactPanel from '../components/wecom/WeComExternalContactPanel';
import WeComCustomerGroupPanel from '../components/wecom/WeComCustomerGroupPanel';
import WeComDirectoryPanel from '../components/wecom/WeComDirectoryPanel';
import './WeComManagementPage.css';

const { Title, Text } = Typography;

export default function WeComManagementPage() {
  const { isAdmin } = useAuth();
  const [selectedCorpId, setSelectedCorpId] = useState('');
  const installations = useQuery({
    queryKey: ['wecom', 'installations'],
    queryFn: fetchWeComInstallations,
    enabled: isAdmin,
    retry: false,
  });
  const selected = useMemo(
    () => installations.data?.find((item) => item.authCorpId === selectedCorpId) ?? installations.data?.[0],
    [installations.data, selectedCorpId],
  );

  if (!isAdmin) return <Result status="403" title="无权访问企业微信管理" />;
  if (installations.isLoading) return <div className="wecom-management-loading"><Spin /></div>;
  if (installations.isError) return <Alert type="error" showIcon message="授权企业加载失败" description="请检查企业微信安装状态后重试。" />;
  if (!installations.data?.length) return <Empty description="暂无已授权企业微信企业" />;

  return (
    <main className="wecom-management" aria-label="企业微信管理">
      <header className="wecom-management-header">
        <div className="wecom-management-title">
          <span className="wecom-status-line" aria-hidden="true" />
          <div>
            <Title level={3}>企业微信管理</Title>
            <Text type="secondary">管理员工作台 · 只读数据按需加载</Text>
          </div>
        </div>
        <Select
          aria-label="授权企业"
          value={selected?.authCorpId}
          onChange={setSelectedCorpId}
          options={installations.data.map((item) => ({
            value: item.authCorpId,
            label: `${item.corpName || '企业名称未同步'} · ${item.authStatus}`,
          }))}
          suffixIcon={<WechatOutlined />}
          style={{ minWidth: 230 }}
        />
      </header>
      <Tabs
        destroyInactiveTabPane={false}
        items={[
          { key: 'app-chat', label: '应用群聊', children: <WeComAppChatPanel authCorpId={selected!.authCorpId} /> },
          { key: 'external-contacts', label: '客户联系', children: <WeComExternalContactPanel authCorpId={selected!.authCorpId} /> },
          { key: 'customer-groups', label: '客户群', children: <WeComCustomerGroupPanel authCorpId={selected!.authCorpId} /> },
          { key: 'directory', label: '通讯录', children: <WeComDirectoryPanel authCorpId={selected!.authCorpId} /> },
        ]}
      />
    </main>
  );
}
