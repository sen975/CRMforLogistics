import { afterEach, describe, expect, it, vi } from 'vitest';

afterEach(() => {
  document.querySelectorAll('script[data-wecom-sdk]').forEach((script) => script.remove());
  delete (window as Window & { ww?: unknown }).ww;
  vi.resetModules();
  vi.useRealTimers();
});

describe('loadWeComSdk', () => {
  it('deduplicates concurrent official SDK loads', async () => {
    const { loadWeComSdk } = await import('./wecomSdk');
    const first = loadWeComSdk();
    const second = loadWeComSdk();
    const scripts = document.querySelectorAll('script[data-wecom-sdk]');
    expect(scripts).toHaveLength(1);
    expect(scripts[0]).toHaveAttribute(
      'src',
      'https://wwcdn.weixin.qq.com/node/open/js/wecom-jssdk-2.3.4.js',
    );
    const sdk = { createWWLoginPanel: vi.fn() };
    (window as Window & { ww?: unknown }).ww = sdk;
    scripts[0].dispatchEvent(new Event('load'));

    await expect(first).resolves.toBe(sdk);
    await expect(second).resolves.toBe(sdk);
  });

  it('clears a failed load so the user can retry', async () => {
    const { loadWeComSdk } = await import('./wecomSdk');
    const failed = loadWeComSdk();
    document.querySelector('script[data-wecom-sdk]')?.dispatchEvent(new Event('error'));
    await expect(failed).rejects.toThrow('企业微信登录组件加载失败');

    const retry = loadWeComSdk();
    expect(document.querySelectorAll('script[data-wecom-sdk]')).toHaveLength(1);
    const sdk = { createWWLoginPanel: vi.fn() };
    (window as Window & { ww?: unknown }).ww = sdk;
    document.querySelector('script[data-wecom-sdk]')?.dispatchEvent(new Event('load'));
    await expect(retry).resolves.toBe(sdk);
  });
});
