import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Drawer, Input, List, Select, Spin, Typography } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { fetchWeComCustomerGroup, searchWeComCustomerGroups } from '../../api/endpoints';
import WeComProviderView from './WeComProviderView';
import { customerGroupIds } from './wecomProviderData';

const { Text } = Typography;

export default function WeComCustomerGroupPanel({ authCorpId }: { authCorpId: string }) {
  const [status, setStatus] = useState<number | undefined>();
  const [owners, setOwners] = useState('');
  const [search, setSearch] = useState(0);
  const [activeGroupId, setActiveGroupId] = useState('');
  const groups = useQuery({
    queryKey: ['wecom', 'customer-groups', authCorpId, status, owners, search],
    queryFn: () => searchWeComCustomerGroups(authCorpId, {
      statusFilter: status,
      ownerFilter: owners.split(',').map((value) => value.trim()).filter(Boolean),
      limit: 100,
    }),
    enabled: !!authCorpId && search > 0,
    retry: false,
  });
  const detail = useQuery({
    queryKey: ['wecom', 'customer-group', authCorpId, activeGroupId],
    queryFn: () => fetchWeComCustomerGroup(authCorpId, activeGroupId, true),
    enabled: !!authCorpId && !!activeGroupId,
    retry: false,
  });
  return (
    <section className="wecom-panel" aria-label="客户群管理">
      <div className="wecom-toolbar wecom-toolbar--wide">
        <Select aria-label="客户群状态" allowClear placeholder="全部状态" value={status} onChange={setStatus}
          options={[{ value: 0, label: '正常' }, { value: 1, label: '跟进人离职' }, { value: 2, label: '离职继承中' }]} />
        <Input aria-label="客户群群主 ID" value={owners} onChange={(event) => setOwners(event.target.value)} placeholder="群主 ID，英文逗号分隔" />
        <Button aria-label="查询客户群" type="primary" icon={<SearchOutlined />} onClick={() => setSearch((value) => value + 1)}>查询客户群</Button>
      </div>
      <Spin spinning={groups.isFetching}>
        {customerGroupIds(groups.data).length > 0 && (
          <List
            size="small"
            header={<Text strong>客户群 ({customerGroupIds(groups.data).length})</Text>}
            dataSource={customerGroupIds(groups.data)}
            renderItem={(groupId) => (
              <List.Item
                actions={[<Button key="detail" type="link" size="small" onClick={() => setActiveGroupId(groupId)}>查看群详情</Button>]}
              >
                <Text copyable={{ text: groupId }}>{groupId}</Text>
              </List.Item>
            )}
          />
        )}
        <WeComProviderView data={groups.data} emptyText={search ? '暂无客户群' : '设置条件后查询客户群'} />
      </Spin>
      <Drawer title="客户群详情" open={!!activeGroupId} onClose={() => setActiveGroupId('')} width="min(640px, 100vw)">
        <Spin spinning={detail.isFetching}><WeComProviderView data={detail.data} /></Spin>
      </Drawer>
    </section>
  );
}
