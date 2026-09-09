import { useEffect, useState } from 'react';
import { Alert, Button, Space, Spin, Typography } from 'antd';
import { CheckOutlined, LinkOutlined } from '@ant-design/icons';
import {
  completeWhatsAppAuthorization,
  createWhatsAppAuthorizationAttempt,
} from '../../api/endpoints';
import type { WhatsAppAuthorizationAttempt } from '../../api/types';

const { Text } = Typography;
const EMBEDDED_SIGNUP_TYPE = 'WA_EMBEDDED_SIGNUP';
const ALLOWED_PROVIDER_ORIGINS = new Set([
  'https://www.facebook.com',
  'https://business.facebook.com',
  'https://chatapp.console.aliyun.com',
]);

type EmbeddedSignupMessage = {
  type?: unknown;
  event?: unknown;
  code?: unknown;
  data?: Record<string, unknown>;
};

function readEmbeddedSignupMessage(message: MessageEvent<unknown>) {
  if (!ALLOWED_PROVIDER_ORIGINS.has(message.origin)) return null;
  if (!message.data || typeof message.data !== 'object') return null;
  const payload = message.data as EmbeddedSignupMessage;
  if (payload.type !== EMBEDDED_SIGNUP_TYPE || typeof payload.event !== 'string') return null;
  const data = payload.data ?? {};
  return {
    event: payload.event,
    wabaId: String(data.waba_id ?? data.wabaId ?? ''),
    phoneNumberId: String(data.phone_number_id ?? data.phoneNumberId ?? ''),
    phoneNumber: String(data.phone_number ?? data.phoneNumber ?? ''),
    code: String(data.code ?? payload.code ?? ''),
    verifiedName: data.verified_name == null ? undefined : String(data.verified_name),
    historySync: data.history_sync === true || data.historySync === true,
  };
}

export function WhatsAppAuthorizationPanel({
  onboardingMode,
  onCompleted,
}: {
  onboardingMode: 'BUSINESS_APP_COEXISTENCE' | 'API_ONLY';
  onCompleted: () => void;
}) {
  const [attempt, setAttempt] = useState<WhatsAppAuthorizationAttempt | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!attempt) return undefined;
    const handleMessage = (message: MessageEvent<unknown>) => {
      const signup = readEmbeddedSignupMessage(message);
      if (!signup) return;
      if (signup.event !== 'FINISH') {
        setError('CAMS 内嵌授权未完成，请重新发起授权');
        return;
      }
      if (!signup.wabaId || !signup.phoneNumberId || !signup.phoneNumber) {
        setError('CAMS 内嵌授权未返回完整账号信息，未创建 CRM 账号');
        return;
      }
      setError(null);
      setBusy(true);
      void completeWhatsAppAuthorization({
        attemptId: attempt.attemptId,
        state: attempt.state,
        event: 'FINISH',
        wabaId: signup.wabaId,
        phoneNumberId: signup.phoneNumberId,
        phoneNumber: signup.phoneNumber,
        verifiedName: signup.verifiedName,
        historySync: signup.historySync,
        code: signup.code,
      }).then(onCompleted).catch(() => {
        setError('CAMS 账号同步失败，未创建 CRM 账号');
      }).finally(() => setBusy(false));
    };
    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
  }, [attempt, onCompleted]);

  const start = async () => {
    setError(null);
    setBusy(true);
    try {
      setAttempt(await createWhatsAppAuthorizationAttempt(onboardingMode));
    } catch {
      setError('无法创建 WhatsApp 授权会话，请稍后重试');
    } finally {
      setBusy(false);
    }
  };

  return <Space direction="vertical" style={{ width: '100%' }}>
    {error && <Alert type="error" showIcon message={error} />}
    {!attempt ? <>
      <Text>{onboardingMode === 'API_ONLY'
        ? '使用 CAMS 内嵌注册完成 WhatsApp Business API 账号授权。'
        : '使用 CAMS 内嵌注册完成 WhatsApp Business App 共存授权。'}</Text>
      <Button type="primary" icon={<LinkOutlined />} loading={busy} onClick={() => void start()}>
        开始 CAMS 内嵌授权
      </Button>
    </> : <Space direction="vertical" style={{ width: '100%' }}>
      <Alert type="info" showIcon message={onboardingMode === 'API_ONLY'
        ? '请在 CAMS 内嵌注册窗口中完成 WhatsApp Business API 注册。CRM 不接收密码、验证码或 AccessKey。'
        : '请在 CAMS 内嵌注册窗口中完成扫码授权。CRM 不接收密码、验证码或 AccessKey。'} />
      <Text type="secondary">授权完成后，CAMS 会发送注册完成事件，系统再同步账号状态。</Text>
      {busy && <Spin size="small" />}
      <Button icon={<CheckOutlined />} disabled={busy}>等待 CAMS 授权回传</Button>
    </Space>}
  </Space>;
}
