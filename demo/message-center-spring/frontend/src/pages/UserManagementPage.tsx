import { useCallback, useEffect, useState } from 'react';
import { App, Button, Card, Form, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { KeyOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { fetchAccountRoles, fetchAdminUsers, replaceAdminUserRoles,
  resetAdminUserPassword } from '../api/endpoints';
import type { AccountRole, AdminUser, AdminUserPage } from '../api/types';

export default function UserManagementPage() {
  const { message } = App.useApp();
  const [data, setData] = useState<AdminUserPage>({ items: [], total: 0, page: 0, size: 20 });
  const [roles, setRoles] = useState<AccountRole[]>([]);
  const [loading, setLoading] = useState(true);
  const [roleTarget, setRoleTarget] = useState<AdminUser | null>(null);
  const [passwordTarget, setPasswordTarget] = useState<AdminUser | null>(null);
  const [saving, setSaving] = useState(false);
  const [roleForm] = Form.useForm<{ roles: string[] }>();
  const [passwordForm] = Form.useForm<{ newPassword: string; confirmPassword: string }>();

  const load = useCallback(async (page = 0, size = data.size) => {
    setLoading(true);
    try {
      const [users, availableRoles] = await Promise.all([
        fetchAdminUsers(page, size), fetchAccountRoles(),
      ]);
      setData(users);
      setRoles(availableRoles);
    } catch { message.error('用户列表加载失败'); }
    finally { setLoading(false); }
  }, [data.size, message]);

  useEffect(() => { void load(0, 20); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const openRoles = (user: AdminUser) => {
    setRoleTarget(user);
    roleForm.setFieldsValue({ roles: user.roles });
  };

  const saveRoles = async () => {
    if (!roleTarget) return;
    const values = await roleForm.validateFields();
    setSaving(true);
    try {
      await replaceAdminUserRoles(roleTarget.id, values.roles);
      setRoleTarget(null);
      await load(data.page, data.size);
      message.success('角色已更新');
    } catch { message.error('角色更新失败'); }
    finally { setSaving(false); }
  };

  const savePassword = async () => {
    if (!passwordTarget) return;
    const values = await passwordForm.validateFields();
    setSaving(true);
    try {
      await resetAdminUserPassword(passwordTarget.id, values.newPassword, values.confirmPassword);
      passwordForm.resetFields();
      setPasswordTarget(null);
      message.success('密码已重置，该用户的旧会话已失效');
    } catch {
      passwordForm.resetFields();
      message.error('密码重置失败');
    }
    finally { setSaving(false); }
  };

  return (
    <Card title={<Typography.Title level={4} style={{ margin: 0 }}>用户管理</Typography.Title>}>
      <Table<AdminUser>
        rowKey="id"
        loading={loading}
        dataSource={data.items}
        pagination={{ current: data.page + 1, pageSize: data.size, total: data.total,
          onChange: (page, size) => void load(page - 1, size) }}
        scroll={{ x: 720 }}
        columns={[
          { title: '昵称', dataIndex: 'displayName' },
          { title: '登录 ID', dataIndex: 'username' },
          { title: '状态', dataIndex: 'status', render: (value) => <Tag>{value}</Tag> },
          { title: '角色', dataIndex: 'roles', render: (values: string[]) => values.map((value) => <Tag key={value}>{value}</Tag>) },
          { title: '操作', key: 'actions', render: (_, user) => <Space>
            <Button aria-label="调整角色" icon={<SafetyCertificateOutlined />} onClick={() => openRoles(user)}>调整角色</Button>
            <Button aria-label="重置密码" icon={<KeyOutlined />} onClick={() => setPasswordTarget(user)}>重置密码</Button>
          </Space> },
        ]}
      />
      <Modal title="调整角色" open={!!roleTarget} onCancel={() => setRoleTarget(null)}
        onOk={() => void saveRoles()} confirmLoading={saving}>
        <Form form={roleForm} layout="vertical">
          <Form.Item name="roles" label="角色" rules={[{ required: true, type: 'array', min: 1 }]}>
            <Select mode="multiple" options={roles.map((role) => ({ value: role.code, label: role.displayName }))} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal title="重置密码" open={!!passwordTarget} onCancel={() => setPasswordTarget(null)}
        onOk={() => void savePassword()} confirmLoading={saving}>
        <Form form={passwordForm} layout="vertical">
          <Form.Item name="newPassword" label="新密码" rules={[{ required: true }, { min: 8, max: 72 }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="confirmPassword" label="确认密码" dependencies={['newPassword']} rules={[
            { required: true },
            ({ getFieldValue }) => ({ validator(_, value) {
              return !value || value === getFieldValue('newPassword')
                ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致'));
            } }),
          ]}>
            <Input.Password />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
