import '@testing-library/jest-dom/vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MessageResponse } from '../../api/types';
import type { WeComViewerHandle } from '../../hooks/useWeComViewer';
import { WeComConversationFrame } from './WeComConversationFrame';

function message(id: string): MessageResponse {
  return {
    id,
    sourceId: `source-${id}`,
    direction: 'inbound',
    kind: 'text',
    subject: '',
    bodyText: id,
    bodyHtml: '',
    channelType: 'wecom',
    from: 'sender',
    to: 'receiver',
    occurredAt: '2026-08-17T00:00:00Z',
    status: 'received',
    ingestSequence: Number(id.replace(/\D/g, '')) || 1,
    attachments: [],
  };
}

function sdkHarness() {
  const createOpenDataFrame = vi.fn((options: any) => {
    const frame = {
      el: document.createElement('iframe'),
      data: options.data,
      setData: vi.fn(async (partialData: Record<string, unknown>) => {
        Object.assign(frame.data, partialData);
        options.handleUpdated?.();
      }),
      dispose: vi.fn(),
    };
    queueMicrotask(() => options.handleMounted?.());
    return frame;
  });
  return {
    sdk: {
      register: vi.fn(),
      initOpenData: vi.fn(async () => undefined),
      createOpenDataFrameFactory: () => ({ createOpenDataFrame }),
    },
    createOpenDataFrame,
  };
}

function prepared(sdk: ReturnType<typeof sdkHarness>['sdk'], id: string) {
  return {
    sdk,
    viewerAuthToken: 'viewer-token',
    viewerSessionId: `session-${id}`,
    messages: [{ msgid: `source-${id}`, secretKey: `secret-${id}` }],
  };
}

beforeEach(() => vi.useRealTimers());

describe('WeComConversationFrame', () => {
  it('A 到 B 只创建一个 frame，并通过 setData 显示 B', async () => {
    const harness = sdkHarness();
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn((contactPointId: string) => Promise.resolve(
        prepared(harness.sdk, contactPointId.endsWith('a') ? 'a' : 'b'),
      )) as WeComViewerHandle['prepareSegment'],
      reportComponentError: vi.fn(),
    };
    const { rerender } = render(
      <ConfigProvider>
        <WeComConversationFrame contactPointId="wecom:contact-a" items={[message('a')]} viewer={viewer} />
      </ConfigProvider>,
    );

    await waitFor(() => expect(harness.createOpenDataFrame).toHaveBeenCalledTimes(1));
    rerender(
      <ConfigProvider>
        <WeComConversationFrame contactPointId="wecom:contact-b" items={[message('b')]} viewer={viewer} />
      </ConfigProvider>,
    );

    await waitFor(() => expect(viewer.prepareSegment).toHaveBeenCalledTimes(2));
    expect(harness.createOpenDataFrame).toHaveBeenCalledTimes(1);
    const frame = harness.createOpenDataFrame.mock.results[0]?.value;
    expect(frame.setData).toHaveBeenCalledWith({ msgList: [{ msgid: 'source-b', secretKey: 'secret-b', direction: 'inbound' }] });
    expect(screen.getByTestId('wecom-conversation-frame-visible')).toBeVisible();
  });

  it('旧联系人请求晚返回时不会覆盖当前联系人', async () => {
    const harness = sdkHarness();
    let resolveA!: (value: any) => void;
    const requestA = new Promise((resolve) => { resolveA = resolve; });
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn((contactPointId: string) => contactPointId.endsWith('a')
        ? requestA
        : Promise.resolve(prepared(harness.sdk, 'b'))) as WeComViewerHandle['prepareSegment'],
      reportComponentError: vi.fn(),
    };
    const { rerender } = render(
      <ConfigProvider>
        <WeComConversationFrame contactPointId="wecom:contact-a" items={[message('a')]} viewer={viewer} />
      </ConfigProvider>,
    );
    rerender(
      <ConfigProvider>
        <WeComConversationFrame contactPointId="wecom:contact-b" items={[message('b')]} viewer={viewer} />
      </ConfigProvider>,
    );
    await waitFor(() => expect(viewer.prepareSegment).toHaveBeenCalledTimes(2));
    resolveA(prepared(harness.sdk, 'a'));
    await act(async () => { await Promise.resolve(); });

    const frame = harness.createOpenDataFrame.mock.results[0]?.value;
    expect(frame.setData).not.toHaveBeenCalledWith({ msgList: [{ msgid: 'source-a', secretKey: 'secret-a', direction: 'inbound' }] });
    expect(frame.setData).toHaveBeenCalledWith({ msgList: [{ msgid: 'source-b', secretKey: 'secret-b', direction: 'inbound' }] });
  });

  it('更新失败时显示错误，而不是永久占位符', async () => {
    const harness = sdkHarness();
    harness.createOpenDataFrame.mockImplementationOnce((options: any) => {
      const frame = {
        el: document.createElement('iframe'),
        data: options.data,
        setData: vi.fn(async () => { throw new Error('update failed'); }),
        dispose: vi.fn(),
      };
      queueMicrotask(() => options.handleMounted?.());
      return frame;
    });
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn().mockResolvedValue(prepared(harness.sdk, 'a')),
      reportComponentError: vi.fn(),
    };

    render(
      <ConfigProvider>
        <WeComConversationFrame contactPointId="wecom:contact-a" items={[message('a')]} viewer={viewer} />
      </ConfigProvider>,
    );

    expect(await screen.findByLabelText('企业微信消息加载失败')).toBeVisible();
  });
});
