import { useEffect, useState } from 'react';
import { DeleteOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Form, Input, Modal, Upload } from 'antd';
import { WeComBindingPanel } from './wecom/WeComBindingPanel';
import { AccountAvatar } from './AccountAvatar';
import { changeAccountPassword, deleteAccountAvatar, updateAccountProfile,
  uploadAccountAvatar } from '../api/endpoints';
import { useAuth } from '../hooks/useAuth';
import './accountPanel.css';

/** 头像来源 → 人话。这里的三个值就是 `AccountProfile['avatar']['source']` 的全集。 */
const AVATAR_SOURCE_LABELS: Record<string, string> = {
  WECOM: '企业微信头像',
  UPLOAD: '上传头像',
  INITIAL: '文字头像',
};

/**
 * 账号抽屉：身份卡 → 资料 → 安全 → 企业微信。
 *
 * 编排上刻意做了两件事：
 * ① 「我是谁」放在最上面一张卡里（头像、昵称、登录名、头像来源），下面三张卡只放「能改什么」；
 * ② 改密码从裸露的三个输入框收进弹窗 —— 它是一个低频、破坏性的动作（会让其他设备掉线），
 *    不该占据抽屉的常驻版面。
 */
export function AccountPanel() {
  const { profile, refreshProfile, replaceSession } = useAuth();
  const { message } = App.useApp();
  const [profileLoading, setProfileLoading] = useState(false);
  const [avatarLoading, setAvatarLoading] = useState(false);
  const [passwordLoading, setPasswordLoading] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [profileForm] = Form.useForm<{ displayName: string }>();
  const [passwordForm] = Form.useForm();

  useEffect(() => {
    if (profile) profileForm.setFieldsValue({ displayName: profile.displayName });
  }, [profile, profileForm]);

  if (!profile) return (
    <div className="mc-account mc-account--empty">
      <p className="mc-account-hint">账号资料暂不可用</p>
      <Button onClick={() => void refreshProfile()}>重新加载</Button>
    </div>
  );

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
      setPasswordOpen(false);
      message.success('密码已修改，其他设备已退出登录');
    } catch {
      // 失败时保留已填内容并让弹窗开着 —— 用户多半是原密码打错了，清空等于让他重敲三遍。
      message.error('密码修改失败，请检查原密码和新密码');
    }
    finally { setPasswordLoading(false); }
  };

  const closePasswordModal = () => {
    passwordForm.resetFields();
    setPasswordOpen(false);
  };

  const canRemoveUploadedAvatar = profile.avatar.source === 'UPLOAD';

  return (
    <div className="mc-account">
      <section className="mc-account-hero">
        <AccountAvatar avatar={profile.avatar} size={56} />
        <div className="mc-account-hero-main">
          <div className="mc-account-hero-name">{profile.displayName}</div>
          <div className="mc-account-hero-username">{profile.username}</div>
          <div className="mc-account-chips">
            <span className="mc-account-chip">
              {AVATAR_SOURCE_LABELS[profile.avatar.source] ?? '头像'}
            </span>
          </div>
        </div>
      </section>

      <section className="mc-account-section">
        <h4 className="mc-account-section-title">资料</h4>
        <Form form={profileForm} onFinish={saveDisplayName}>
          <div className="mc-account-field">
            <span className="mc-account-field-label">昵称</span>
            <Form.Item
              name="displayName"
              className="mc-account-field-control"
              rules={[
                { required: true, message: '请输入昵称' },
                { max: 50, message: '昵称最多 50 个字符' },
              ]}
            >
              <Input placeholder="昵称" />
            </Form.Item>
            <Button htmlType="submit" type="primary" loading={profileLoading}>保存昵称</Button>
          </div>
        </Form>
        <div className="mc-account-field">
          <span className="mc-account-field-label">头像</span>
          <div className="mc-account-field-control mc-account-field-actions">
            <Upload accept="image/jpeg,image/png" showUploadList={false} beforeUpload={uploadAvatar}>
              <Button icon={<UploadOutlined />} loading={avatarLoading}>上传头像</Button>
            </Upload>
            {canRemoveUploadedAvatar && (
              <Button icon={<DeleteOutlined />} loading={avatarLoading} onClick={removeAvatar}>
                删除上传头像
              </Button>
            )}
          </div>
        </div>
        <p className="mc-account-hint">支持 2 MiB 以内的 JPEG 或 PNG。企业微信头像优先展示。</p>
      </section>

      <section className="mc-account-section">
        <h4 className="mc-account-section-title">安全</h4>
        <div className="mc-account-action-row">
          <div className="mc-account-action-text">
            <div className="mc-account-action-title">登录密码</div>
            <div className="mc-account-action-hint">修改后，其他设备上的登录会失效</div>
          </div>
          <Button onClick={() => setPasswordOpen(true)}>重新设置密码</Button>
        </div>
      </section>

      <section className="mc-account-section">
        <h4 className="mc-account-section-title">企业微信</h4>
        <WeComBindingPanel />
      </section>

      <Modal
        title="重新设置密码"
        rootClassName="mc-account-modal"
        open={passwordOpen}
        width={420}
        okText="确认修改"
        cancelText="取消"
        confirmLoading={passwordLoading}
        onOk={() => passwordForm.submit()}
        onCancel={closePasswordModal}
        afterClose={() => passwordForm.resetFields()}
      >
        <Form form={passwordForm} layout="vertical" onFinish={changePassword} requiredMark={false}>
          <Form.Item name="currentPassword" label="原密码" rules={[{ required: true, message: '请输入原密码' }]}>
            <Input.Password autoComplete="current-password" placeholder="当前登录密码" />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 8, max: 72, message: '新密码长度需在 8 到 72 个字符之间' },
            ]}
          >
            <Input.Password autoComplete="new-password" placeholder="至少 8 个字符" />
          </Form.Item>
          <Form.Item
            name="confirmPassword"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({ validator(_, value) {
                return !value || value === getFieldValue('newPassword')
                  ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致'));
              } }),
            ]}
          >
            <Input.Password autoComplete="new-password" placeholder="再输入一次新密码" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
