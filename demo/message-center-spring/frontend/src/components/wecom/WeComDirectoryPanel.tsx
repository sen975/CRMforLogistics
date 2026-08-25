import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Avatar, Button, InputNumber, Segmented, Spin, Switch, Table, Typography, message } from 'antd';
import { listWeComDepartments, listWeComDirectoryMembers, listWeComTags, syncWeComDirectoryProfiles } from '../../api/endpoints';
import WeComProviderView from './WeComProviderView';
import { directoryDepartments, directoryMembers } from './wecomProviderData';

const { Text } = Typography;

function errorMessage(error: unknown): string {
  if (typeof error === 'object' && error !== null) {
    const response = (error as { response?: { data?: { message?: unknown; code?: unknown } } }).response;
    const message = response?.data?.message;
    const code = response?.data?.code;
    if (typeof message === 'string' && message.trim()) return message;
    if (typeof code === 'string' && code.trim()) return code;
  }
  if (error instanceof Error && error.message) return error.message;
  return '企业微信通讯录加载失败';
}

export default function WeComDirectoryPanel({ authCorpId }: { authCorpId: string }) {
  const [view, setView] = useState<'成员' | '部门' | '标签'>('成员');
  const [departmentId, setDepartmentId] = useState(1);
  const [fetchChild, setFetchChild] = useState(true);
  const members = useQuery({
    queryKey: ['wecom', 'directory', 'members', authCorpId, departmentId, fetchChild],
    queryFn: () => listWeComDirectoryMembers(authCorpId, departmentId, fetchChild),
    enabled: !!authCorpId && view === '成员', retry: false,
  });
  const departments = useQuery({
    queryKey: ['wecom', 'directory', 'departments', authCorpId],
    queryFn: () => listWeComDepartments(authCorpId),
    enabled: !!authCorpId && (view === '部门' || view === '成员'), retry: false,
  });
  const tags = useQuery({
    queryKey: ['wecom', 'directory', 'tags', authCorpId],
    queryFn: () => listWeComTags(authCorpId),
    enabled: !!authCorpId && view === '标签', retry: false,
  });
  const profileSync = useMutation({
    mutationFn: () => syncWeComDirectoryProfiles(authCorpId),
    onSuccess: (result) => message.success(`已同步 ${result.discovered} 位成员资料`),
  });
  const active = view === '成员' ? members : view === '部门' ? departments : tags;
  return (
    <section className="wecom-panel" aria-label="通讯录">
      <div className="wecom-toolbar wecom-directory-toolbar">
        <Segmented value={view} onChange={(value) => setView(value as typeof view)} options={['成员', '部门', '标签']} />
        {view === '成员' && <>
          <InputNumber aria-label="通讯录部门 ID" min={1} value={departmentId} onChange={(value) => setDepartmentId(value ?? 1)} />
          <Text>包含子部门</Text><Switch checked={fetchChild} onChange={setFetchChild} />
          <Button size="small" loading={profileSync.isPending}
            onClick={() => profileSync.mutate()} aria-label="同步成员资料">同步成员资料</Button>
        </>}
      </div>
      {profileSync.isError && <Alert type="error" showIcon message={errorMessage(profileSync.error)} />}
      {active.isError && (
        <Alert
          type="error"
          showIcon
          message={errorMessage(active.error)}
          action={<Button size="small" onClick={() => void active.refetch()}>重试</Button>}
        />
      )}
      <Spin spinning={active.isFetching}>
        {view === '成员' ? (
          <Table
            size="small"
            pagination={false}
            rowKey="userId"
            dataSource={directoryMembers(members.data)}
            locale={{ emptyText: '该部门暂无成员' }}
            columns={[
              {
                title: '成员', key: 'member',
                render: (_: unknown, member: ReturnType<typeof directoryMembers>[number]) => (
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                    <Avatar size="small" src={member.avatar}>{member.name.slice(0, 1)}</Avatar>
                    <span>{member.name}</span>
                  </span>
                ),
              },
              { title: 'UserID', dataIndex: 'userId', key: 'userId' },
              { title: '部门', key: 'department', render: (_: unknown, member: ReturnType<typeof directoryMembers>[number]) => member.department?.join(', ') || '-' },
            ]}
          />
        ) : view === '部门' ? (
          <Table
            size="small"
            pagination={false}
            rowKey="id"
            dataSource={directoryDepartments(departments.data)}
            locale={{ emptyText: '暂无部门' }}
            columns={[
              { title: '部门', dataIndex: 'name', key: 'name' },
              { title: '部门 ID', dataIndex: 'id', key: 'id' },
              {
                title: '成员', key: 'members',
                render: (_: unknown, department: ReturnType<typeof directoryDepartments>[number]) => (
                  <Button type="link" size="small" onClick={() => { setDepartmentId(department.id); setView('成员'); }}>
                    查看成员
                  </Button>
                ),
              },
            ]}
          />
        ) : <WeComProviderView data={active.data} />}
      </Spin>
    </section>
  );
}
