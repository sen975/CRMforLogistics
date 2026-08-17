const WECOM_LOGIN_SDK_URL = 'https://wwcdn.weixin.qq.com/node/open/js/wecom-jssdk-2.3.4.js';
const SDK_LOAD_TIMEOUT_MS = 10_000;

export interface WeComLoginPanelOptions {
  el: string;
  params: {
    login_type: string;
    appid: string;
    redirect_uri: string;
    state: string;
    redirect_type: 'callback';
    panel_size: 'small';
    lang: 'zh';
  };
  onLoginSuccess: (result: { code: string }) => void | Promise<void>;
  onLoginFail: (error?: unknown) => void;
}

export interface WeComSdk {
  createWWLoginPanel: (options: WeComLoginPanelOptions) => unknown;
}

declare global {
  interface Window {
    ww?: WeComSdk;
  }
}

let loginSdkPromise: Promise<WeComSdk> | null = null;

export function loadWeComSdk(): Promise<WeComSdk> {
  if (window.top !== window.self) {
    return Promise.reject(new Error('企业微信登录组件不能在 iframe 中加载'));
  }
  if (window.ww?.createWWLoginPanel) return Promise.resolve(window.ww);
  if (loginSdkPromise) return loginSdkPromise;

  loginSdkPromise = new Promise<WeComSdk>((resolve, reject) => {
    const staleScript = document.querySelector<HTMLScriptElement>('script[data-wecom-sdk]');
    staleScript?.remove();

    const script = document.createElement('script');
    script.src = WECOM_LOGIN_SDK_URL;
    script.async = true;
    script.dataset.wecomSdk = 'login';

    const timeout = window.setTimeout(() => fail('企业微信登录组件加载超时'), SDK_LOAD_TIMEOUT_MS);
    const cleanup = () => {
      window.clearTimeout(timeout);
      script.removeEventListener('load', handleLoad);
      script.removeEventListener('error', handleError);
    };
    const fail = (message: string) => {
      cleanup();
      script.remove();
      loginSdkPromise = null;
      reject(new Error(message));
    };
    const handleLoad = () => {
      if (!window.ww?.createWWLoginPanel) {
        fail('企业微信登录组件加载失败');
        return;
      }
      cleanup();
      resolve(window.ww);
    };
    const handleError = () => fail('企业微信登录组件加载失败');

    script.addEventListener('load', handleLoad);
    script.addEventListener('error', handleError);
    document.head.appendChild(script);
  });

  return loginSdkPromise;
}
