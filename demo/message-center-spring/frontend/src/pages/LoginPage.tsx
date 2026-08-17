import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Button, Typography, App, Tabs } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useAuth } from '../hooks/useAuth';
import { WeComLoginPanel } from '../components/wecom/WeComLoginPanel';

const { Title } = Typography;

export default function LoginPage() {
  const [loading, setLoading] = useState(false);
  const { login, loginWithWeCom, isAuthenticated } = useAuth();
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
      message.error('用户名或密码错误');
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
    <Form onFinish={handleSubmit} size="large">
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
    </Form>
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
