import { useEffect, useState } from 'react';
import { DeleteOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Divider, Flex, Form, Input, Tag, Typography, Upload } from 'antd';
import { WeComBindingPanel } from './wecom/WeComBindingPanel';
import { AccountAvatar } from './AccountAvatar';
import { changeAccountPassword, deleteAccountAvatar, updateAccountProfile,
  uploadAccountAvatar } from '../api/endpoints';
import { useAuth } from '../hooks/useAuth';

const { Text, Title } = Typography;

export function AccountPanel() {
  const { profile, refreshProfile, replaceSession } = useAuth();
  const { message } = App.useApp();
  const [profileLoading, setProfileLoading] = useState(false);
  const [avatarLoading, setAvatarLoading] = useState(false);
  const [passwordLoading, setPasswordLoading] = useState(false);
  const [profileForm] = Form.useForm<{ displayName: string }>();
  const [passwordForm] = Form.useForm();

  useEffect(() => {
    if (profile) profileForm.setFieldsValue({ displayName: profile.displayName });
  }, [profile, profileForm]);

  if (!profile) return <Flex vertical gap={8}>
    <Typography.Text type="secondary">账号资料暂不可用</Typography.Text>
    <Button onClick={() => void refreshProfile()} style={{ width: 'fit-content' }}>重新加载</Button>
  </Flex>;

  const saveDisplayName = async ({ displayName }: { displayName: string }) => {
    setProfileLoading(true);
    try {
      await updateAccountProfile(displayName);
      await refreshProfile();
      message.success('昵称已更新');
    } catch { message.error('昵称更新失败'); }
    finally { setProfileLoading(false); }
  };

  const uploadAvatar = async (file: File) => {
    setAvatarLoading(true);
    try {
      await uploadAccountAvatar(file);
      await refreshProfile();
      message.success('头像已更新');
    } catch { message.error('头像上传失败，请使用 2 MiB 内的 JPEG 或 PNG'); }
    finally { setAvatarLoading(false); }
    return false;
  };

  const removeAvatar = async () => {
    setAvatarLoading(true);
    try {
      await deleteAccountAvatar();
      await refreshProfile();
      message.success('已删除上传头像');
    } catch { message.error('头像删除失败'); }
    finally { setAvatarLoading(false); }
  };

  const changePassword = async (values: {
    currentPassword: string; newPassword: string; confirmPassword: string;
  }) => {
    setPasswordLoading(true);
    try {
      const nextSession = await changeAccountPassword(values);
      replaceSession(nextSession);
      passwordForm.resetFields();
      message.success('密码已修改，其他设备已退出登录');
    } catch {
      passwordForm.resetFields();
      message.error('密码修改失败，请检查原密码和新密码');
    }
    finally { setPasswordLoading(false); }
  };

  return (
    <Flex vertical gap={12}>
      <Flex align="center" gap={10}>
        <AccountAvatar avatar={profile.avatar} size={52} />
        <Flex vertical>
          <Text strong>{profile.displayName}</Text>
          <Text type="secondary">{profile.username}</Text>
          <Tag style={{ width: 'fit-content', marginTop: 4 }}>
            {profile.avatar.source === 'WECOM' ? '企业微信头像'
              : profile.avatar.source === 'UPLOAD' ? '上传头像' : '文字头像'}
          </Tag>
        </Flex>
      </Flex>
      <Divider style={{ margin: '16px 0' }} />
      <Title level={5} style={{ marginTop: 0 }}>昵称</Title>
      <Form form={profileForm} layout="vertical" onFinish={saveDisplayName}>
        <Form.Item name="displayName" rules={[{ required: true }, { max: 50 }]}>
          <Input placeholder="昵称" />
        </Form.Item>
        <Button htmlType="submit" type="primary" loading={profileLoading}>保存昵称</Button>
      </Form>
      <Divider />
      <Title level={5} style={{ marginTop: 0 }}>头像</Title>
      <Flex gap={8} wrap>
        <Upload accept="image/jpeg,image/png" showUploadList={false} beforeUpload={uploadAvatar}>
          <Button icon={<UploadOutlined />} loading={avatarLoading}>上传头像</Button>
        </Upload>
        <Button icon={<DeleteOutlined />} loading={avatarLoading} onClick={removeAvatar}>删除上传头像</Button>
      </Flex>
      <Divider />
      <Title level={5} style={{ marginTop: 0 }}>密码</Title>
      <Form form={passwordForm} layout="vertical" onFinish={changePassword}>
        <Form.Item name="currentPassword" label="原密码" rules={[{ required: true }]}>
          <Input.Password autoComplete="current-password" />
        </Form.Item>
        <Form.Item name="newPassword" label="新密码" rules={[{ required: true }, { min: 8, max: 72 }]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item name="confirmPassword" label="确认新密码" dependencies={['newPassword']} rules={[
          { required: true },
          ({ getFieldValue }) => ({ validator(_, value) {
            return !value || value === getFieldValue('newPassword')
              ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致'));
          } }),
        ]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Button htmlType="submit" type="primary" loading={passwordLoading}>修改密码</Button>
      </Form>
      <Divider />
      <Title level={5} style={{ marginTop: 0 }}>企业微信</Title>
      <WeComBindingPanel />
    </Flex>
  );
}
