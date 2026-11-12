import * as weComViewerSdk from '@wecom/jssdk';

const WECOM_LOGIN_SDK_URL = 'https://wwcdn.weixin.qq.com/node/open/js/wecom-jssdk-2.3.4.js';
const SDK_LOAD_TIMEOUT_MS = 10_000;

export interface WeComLoginPanelOptions {
  el: string;
  params: {
    login_type: string;
    appid: string;
    agentid?: string;
    redirect_uri: string;
    state: string;
    redirect_type: 'callback';
    panel_size: 'small';
    lang: 'zh';
  };
  onLoginSuccess: (result: { code: string }) => void | Promise<void>;
  onLoginFail: (error?: unknown) => void;
}

export interface WeComLoginSdk {
  createWWLoginPanel: (options: WeComLoginPanelOptions) => unknown;
}

export interface WeComRegisterOptions {
  corpId: string;
  agentId: string;
  jsApiList: string[];
  getConfigSignature: (url?: string) => WeComSignatureData | Promise<WeComSignatureData>;
  getAgentConfigSignature: (url?: string) => WeComSignatureData | Promise<WeComSignatureData>;
}

export interface WeComSignatureData {
  timestamp: string | number;
  nonceStr: string;
  signature: string;
}

export interface WeComOpenDataFrame {
  el: HTMLElement;
  data?: Record<string, unknown>;
  setData?: (partialData: Record<string, unknown>) => Promise<void>;
  dispose: () => void;
}

export interface WeComScrollViewContext {
  scrollTo: (options: { top?: number; left?: number }) => void;
}

export interface WeComOpenDataFrameOptions {
  el: HTMLElement;
  template: string;
  style: string;
  data: Record<string, unknown>;
  methods?: Record<string, (event: unknown) => void>;
  handleMounted?: () => void;
  handleUpdated?: () => void;
  handleError?: (error: unknown) => void;
  handleModal?: (event: { modalUrl?: string; modalSize?: { width?: number; height?: number } }) => boolean;
  error?: (error: unknown) => void;
}

export interface WeComOpenDataFrameFactory {
  createOpenDataFrame: (options: WeComOpenDataFrameOptions) => WeComOpenDataFrame;
}

export interface WeComViewerSdk {
  register: (options: WeComRegisterOptions) => void;
  initOpenData: () => Promise<unknown>;
  createOpenDataFrameFactory: () => WeComOpenDataFrameFactory;
  createScrollViewContext?: (frame: WeComOpenDataFrame, refName: string) => Promise<WeComScrollViewContext | undefined>;
}

declare global {
  interface Window {
    ww?: WeComLoginSdk;
  }
}

let loginSdkPromise: Promise<WeComLoginSdk> | null = null;
let viewerSdkPromise: Promise<WeComViewerSdk> | null = null;
const WECOM_VIEWER_SDK_URL = 'https://open.work.weixin.qq.com/wwopen/js/jwxwork-1.0.0.js';

export function loadWeComSdk(): Promise<WeComLoginSdk> {
  if (window.top !== window.self) {
    return Promise.reject(new Error('企业微信登录组件不能在 iframe 中加载'));
  }
  if (window.ww?.createWWLoginPanel) return Promise.resolve(window.ww);
  if (loginSdkPromise) return loginSdkPromise;

  loginSdkPromise = new Promise<WeComLoginSdk>((resolve, reject) => {
    const staleScript = document.querySelector<HTMLScriptElement>('script[data-wecom-sdk="login"]');
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

export function loadWeComViewerSdk(): Promise<WeComViewerSdk> {
  if (viewerSdkPromise) return viewerSdkPromise;
  if (!weComViewerSdk.register || !weComViewerSdk.initOpenData || !weComViewerSdk.createOpenDataFrameFactory) {
    return Promise.reject(new Error('企业微信会话组件 SDK 不可用'));
  }

  viewerSdkPromise = new Promise<WeComViewerSdk>((resolve, reject) => {
    const staleScript = document.querySelector<HTMLScriptElement>('script[data-wecom-sdk="viewer"]');
    staleScript?.remove();
    const script = document.createElement('script');
    script.src = WECOM_VIEWER_SDK_URL;
    script.async = true;
    script.dataset.wecomSdk = 'viewer';

    const timeout = window.setTimeout(() => fail('企业微信会话组件加载超时'), SDK_LOAD_TIMEOUT_MS);
    const cleanup = () => {
      window.clearTimeout(timeout);
      script.removeEventListener('load', handleLoad);
      script.removeEventListener('error', handleError);
    };
    const fail = (message: string) => {
      cleanup();
      script.remove();
      viewerSdkPromise = null;
      reject(new Error(message));
    };
    const handleLoad = () => {
      cleanup();
      resolve({
        register: weComViewerSdk.register,
        initOpenData: weComViewerSdk.initOpenData,
        createOpenDataFrameFactory: weComViewerSdk.createOpenDataFrameFactory,
        createScrollViewContext: weComViewerSdk.createScrollViewContext
          ? (frame, refName) => weComViewerSdk.createScrollViewContext!(frame as never, refName) as Promise<WeComScrollViewContext | undefined>
          : undefined,
      });
    };
    const handleError = () => fail('企业微信会话组件加载失败');

    script.addEventListener('load', handleLoad);
    script.addEventListener('error', handleError);
    document.head.appendChild(script);
  }).catch((error) => {
    viewerSdkPromise = null;
    throw error;
  });

  return viewerSdkPromise;
}
