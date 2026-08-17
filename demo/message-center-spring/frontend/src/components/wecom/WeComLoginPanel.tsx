import { ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Flex, Spin } from 'antd';
import { useEffect, useId, useState } from 'react';
import { createWeComAttempt } from '../../api/endpoints';
import { loadWeComSdk } from '../../wecom/wecomSdk';

interface WeComLoginPanelProps {
  purpose: 'login' | 'bind';
  onAuthenticated: (code: string, state: string) => void | Promise<void>;
}

export function WeComLoginPanel({ purpose, onAuthenticated }: WeComLoginPanelProps) {
  const hostId = `wecom-login-${useId().replace(/:/g, '')}`;
  const [retryKey, setRetryKey] = useState(0);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');

  useEffect(() => {
    let disposed = false;
    setStatus('loading');

    void createWeComAttempt(purpose)
      .then(async (attempt) => {
        const ww = await loadWeComSdk();
        if (disposed) return;
        ww.createWWLoginPanel({
          el: `#${hostId}`,
          params: {
            login_type: attempt.loginType,
            appid: attempt.appId,
            redirect_uri: attempt.redirectUri,
            state: attempt.state,
            redirect_type: 'callback',
            panel_size: 'small',
            lang: 'zh',
          },
          onLoginSuccess: ({ code }) => onAuthenticated(code, attempt.state),
          onLoginFail: () => setStatus('error'),
        });
        setStatus('ready');
      })
      .catch(() => {
        if (!disposed) setStatus('error');
      });

    return () => {
      disposed = true;
      document.getElementById(hostId)?.replaceChildren();
    };
  }, [hostId, onAuthenticated, purpose, retryKey]);

  return (
    <Flex vertical align="center" gap={16} style={{ minHeight: 300, justifyContent: 'center' }}>
      {status === 'loading' && <Spin />}
      {status === 'error' && (
        <Alert
          type="error"
          showIcon
          message="企业微信登录组件加载失败，请重试"
          action={(
            <Button
              type="text"
              icon={<ReloadOutlined />}
              aria-label="重新加载企业微信登录组件"
              onClick={() => setRetryKey((value) => value + 1)}
            />
          )}
        />
      )}
      <div
        id={hostId}
        aria-label="企业微信官方登录组件"
        style={{ display: status === 'ready' ? 'block' : 'none' }}
      />
    </Flex>
  );
}
