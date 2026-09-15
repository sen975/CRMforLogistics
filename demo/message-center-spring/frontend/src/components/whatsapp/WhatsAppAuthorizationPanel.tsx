import { useEffect, useState } from 'react';
import { Alert, Button, Input, Space, Spin, Typography } from 'antd';
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
]);

type MetaSdk = {
  init: (options: { appId: string; cookie: boolean; xfbml: boolean; version: string }) => void;
  login: (callback: (response: unknown) => void, options: Record<string, unknown>) => void;
};

declare global {
  interface Window { FB?: MetaSdk }
}

function loadMetaSdk(): Promise<MetaSdk> {
  if (window.FB) return Promise.resolve(window.FB);
  const existing = document.querySelector<HTMLScriptElement>('script[data-whatsapp-meta-sdk]');
  const script = existing ?? document.createElement('script');
  if (!existing) {
    script.src = 'https://connect.facebook.net/en_US/sdk.js';
    script.async = true;
    script.defer = true;
    script.dataset.whatsappMetaSdk = 'true';
    document.head.appendChild(script);
  }
  return new Promise((resolve, reject) => {
    const finish = () => window.FB ? resolve(window.FB) : reject(new Error('Meta SDK unavailable'));
    script.addEventListener('load', finish, { once: true });
    script.addEventListener('error', () => reject(new Error('Meta SDK load failed')), { once: true });
  });
}

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
    code: String(data.code ?? payload.code ?? ''),
  };
}

export function WhatsAppAuthorizationPanel({
  onboardingMode,
  onCompleted,
  }: {
  onboardingMode: 'BUSINESS_APP_COEXISTENCE' | 'API_ONLY' | 'ADMIN_API_WABA';
  onCompleted: () => void;
}) {
  const [attempt, setAttempt] = useState<WhatsAppAuthorizationAttempt | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!attempt) return undefined;
    let cancelled = false;
    const profile = attempt.startupProfile;
    if (profile?.appId && profile.configId) {
      setBusy(true);
      void loadMetaSdk().then((sdk) => {
        if (cancelled) return;
        sdk.init({ appId: profile.appId, cookie: false, xfbml: false, version: 'v21.0' });
        sdk.login(() => undefined, {
          config_id: profile.configId,
          response_type: 'code',
          override_default_response_type: true,
          extras: { feature: 'whatsapp_embedded_signup', sessionInfoVersion: 2 },
        });
      }).catch(() => {
        if (!cancelled) setError('Meta 内嵌授权组件加载失败，请稍后重试');
      }).finally(() => {
        if (!cancelled) setBusy(false);
      });
    }
    const handleMessage = (message: MessageEvent<unknown>) => {
      const signup = readEmbeddedSignupMessage(message);
      if (!signup) return;
      if (signup.event !== 'FINISH') {
        setError('CAMS 内嵌授权未完成，请重新发起授权');
        return;
      }
      if (!signup.wabaId || !signup.phoneNumberId || !signup.code) {
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
        code: signup.code,
      }).then(onCompleted).catch(() => {
        setError('CAMS 账号同步失败，未创建 CRM 账号');
      }).finally(() => setBusy(false));
    };
    window.addEventListener('message', handleMessage);
    return () => { cancelled = true; window.removeEventListener('message', handleMessage); };
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

  if (onboardingMode === 'API_ONLY') return <WhatsAppApiPhoneWizard onCompleted={onCompleted} />;

  return <Space direction="vertical" style={{ width: '100%' }}>
    {error && <Alert type="error" showIcon message={error} />}
    {!attempt ? <>
      <Text>{onboardingMode === 'ADMIN_API_WABA' ? '使用 Meta 官方内嵌注册完成企业 WhatsApp Business API/WABA 授权。' : '使用 Meta 官方内嵌注册完成 WhatsApp Business App 共存授权。'}</Text>
      <Button type="primary" icon={<LinkOutlined />} loading={busy} onClick={() => void start()}>
        开始 CAMS 内嵌授权
      </Button>
    </> : <Space direction="vertical" style={{ width: '100%' }}>
      <Alert type="info" showIcon message="请在 CAMS 内嵌注册窗口中完成扫码授权。CRM 不接收密码、验证码或 AccessKey。" />
      <Text type="secondary">授权完成后，CAMS 会发送注册完成事件，系统再同步账号状态。</Text>
      {busy && <Spin size="small" />}
      <Button icon={<CheckOutlined />} disabled={busy}>等待 CAMS 授权回传</Button>
    </Space>}
  </Space>;
}

function WhatsAppApiPhoneWizard({ onCompleted }: { onCompleted: () => void }) {
  const [operation, setOperation] = useState<import('../../api/types').WhatsAppPhoneOperationStatus | null>(null);
  const [phone, setPhone] = useState('');
  const [countryCode, setCountryCode] = useState('86');
  const [verifiedName, setVerifiedName] = useState('');
  const [accountName, setAccountName] = useState('');
  const [accountRemark, setAccountRemark] = useState('');
  const [code, setCode] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const start = async () => {
    setBusy(true); setError(null);
    try {
      const { addWhatsAppPhoneNumber } = await import('../../api/endpoints');
      setOperation(await addWhatsAppPhoneNumber({ countryCode, phoneNumber: phone, verifiedName, accountName, accountRemark }));
    } catch { setError('无法创建企业 API 新号码操作'); } finally { setBusy(false); }
  };
  const sendCode = async () => {
    if (!operation || !confirmed) { setError('请先确认号码由你本人控制'); return; }
    setBusy(true); setError(null);
    try {
      const { sendWhatsAppVerificationCode } = await import('../../api/endpoints');
      setOperation(await sendWhatsAppVerificationCode(operation.operationId, { locale: 'zh_CN', method: 'sms', confirmed: true }));
    } catch { setError('验证码发送失败'); } finally { setBusy(false); }
  };
  const verify = async () => {
    if (!operation || !code) return;
    setBusy(true); setError(null);
    try {
      const { verifyWhatsAppPhoneNumber } = await import('../../api/endpoints');
      const result = await verifyWhatsAppPhoneNumber(operation.operationId, code);
      setCode(''); setOperation(result);
      if (result.status === 'REGISTERED') onCompleted();
    } catch { setCode(''); setError('号码验证失败，请检查验证码或稍后重试'); } finally { setBusy(false); }
  };
  return <Space direction="vertical" style={{ width: '100%' }}>
    {error && <Alert type="error" showIcon message={error} />}
    {!operation ? <>
      <Input addonBefore={<Input value={countryCode} onChange={(e) => setCountryCode(e.target.value)} style={{ width: 72 }} />} placeholder="手机号" value={phone} onChange={(e) => setPhone(e.target.value)} />
      <Input placeholder="WhatsApp 显示名称" value={verifiedName} onChange={(e) => setVerifiedName(e.target.value)} />
      <Input placeholder="账号名称" value={accountName} onChange={(e) => setAccountName(e.target.value)} />
      <Input placeholder="备注（可选）" value={accountRemark} onChange={(e) => setAccountRemark(e.target.value)} />
      <Button type="primary" loading={busy} onClick={() => void start()}>创建号码操作</Button>
    </> : operation.status === 'PENDING' ? <>
      <Alert type="info" showIcon message={`号码尾号 ${operation.phoneNumberLast4} 已提交，请确认后发送验证码。`} />
      <label><input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} /> 我确认可以控制该号码并同意发送验证码</label>
      <Button type="primary" loading={busy} disabled={!confirmed} onClick={() => void sendCode()}>发送验证码</Button>
    </> : <>
      <Alert type="info" showIcon message={`请输入发送到尾号 ${operation.phoneNumberLast4} 的验证码。`} />
      <Input.Password value={code} onChange={(e) => setCode(e.target.value)} placeholder="验证码" />
      <Button type="primary" loading={busy} disabled={!code} onClick={() => void verify()}>验证并绑定</Button>
    </>}
  </Space>;
}
