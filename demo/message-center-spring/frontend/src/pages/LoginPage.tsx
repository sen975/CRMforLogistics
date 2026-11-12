import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Button, Typography, App, Tabs, Segmented } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useAuth } from '../hooks/useAuth';
import { WeComLoginPanel } from '../components/wecom/WeComLoginPanel';

const { Title } = Typography;

export default function LoginPage() {
  const [loading, setLoading] = useState(false);
  const [passwordMode, setPasswordMode] = useState<'login' | 'register'>('login');
  const [loginForm] = Form.useForm();
  const [registerForm] = Form.useForm();
  const { login, register, loginWithWeCom, isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const { message } = App.useApp();

  useEffect(() => {
    if (isAuthenticated) navigate('/', { replace: true });
  }, [isAuthenticated, navigate]);

  const handleSubmit = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      await login(values.username, values.password);
    } catch {
      loginForm.resetFields(['password']);
      message.error('用户名或密码错误');
    } finally {
      setLoading(false);
    }
  };

  const handleRegister = async (values: {
    username: string; displayName?: string; password: string; confirmPassword: string;
  }) => {
    setLoading(true);
    try {
      await register({ username: values.username, displayName: values.displayName, password: values.password });
    } catch {
      registerForm.resetFields(['password', 'confirmPassword']);
      message.error('注册失败，请检查登录 ID 是否已存在');
    } finally {
      setLoading(false);
    }
  };

  const handleWeComAuthenticated = useCallback(async (code: string, state: string) => {
    try {
      await loginWithWeCom(code, state);
    } catch {
      message.error('企业微信登录失败，请重试');
    }
  }, [loginWithWeCom, message]);

  if (isAuthenticated) return null;

  const passwordLogin = (
    <>
      <Segmented
        block
        value={passwordMode}
        onChange={(value) => setPasswordMode(value as 'login' | 'register')}
        options={[{ label: '登录', value: 'login' }, { label: '注册', value: 'register' }]}
        style={{ marginBottom: 20 }}
      />
      {passwordMode === 'login' ? <Form form={loginForm} onFinish={handleSubmit} size="large">
      <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
        <Input prefix={<UserOutlined />} placeholder="用户名" autoFocus />
      </Form.Item>
      <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
        <Input.Password prefix={<LockOutlined />} placeholder="密码" />
      </Form.Item>
      <Form.Item style={{ marginBottom: 0 }}>
        <Button type="primary" htmlType="submit" loading={loading} block>
          登录
        </Button>
      </Form.Item>
      </Form> : <Form form={registerForm} onFinish={handleRegister} size="large">
        <Form.Item name="username" rules={[
          { required: true, message: '请输入登录 ID' },
          { pattern: /^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$/, message: '请输入 3-32 位合法登录 ID' },
        ]}>
          <Input prefix={<UserOutlined />} placeholder="登录 ID" autoFocus />
        </Form.Item>
        <Form.Item name="displayName" rules={[{ max: 50, message: '昵称最多 50 个字符' }]}>
          <Input prefix={<UserOutlined />} placeholder="昵称" />
        </Form.Item>
        <Form.Item name="password" rules={[
          { required: true, message: '请输入密码' },
          { min: 8, max: 72, message: '密码长度为 8-72 位' },
        ]}>
          <Input.Password prefix={<LockOutlined />} placeholder="密码" />
        </Form.Item>
        <Form.Item name="confirmPassword" dependencies={['password']} rules={[
          { required: true, message: '请确认密码' },
          ({ getFieldValue }) => ({ validator(_, value) {
            return !value || value === getFieldValue('password')
              ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致'));
          } }),
        ]}>
          <Input.Password prefix={<LockOutlined />} placeholder="确认密码" />
        </Form.Item>
        <Form.Item style={{ marginBottom: 0 }}>
          <Button type="primary" htmlType="submit" loading={loading} block>注册并登录</Button>
        </Form.Item>
      </Form>}
    </>
  );

  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: '100dvh',
        background: '#f5f5f5',
        padding: 16,
        boxSizing: 'border-box',
      }}
    >
      <Card style={{ width: 'min(400px, 100%)', boxShadow: '0 2px 8px rgba(0,0,0,0.1)' }}>
        <Title level={3} style={{ textAlign: 'center', marginBottom: 24 }}>
          统一消息中心
        </Title>
        <Tabs
          defaultActiveKey="password"
          destroyOnHidden
          centered
          items={[
            { key: 'password', label: '账号密码', children: passwordLogin },
            {
              key: 'wecom',
              label: '企业微信登录',
              children: (
                <WeComLoginPanel purpose="login" onAuthenticated={handleWeComAuthenticated} />
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}
