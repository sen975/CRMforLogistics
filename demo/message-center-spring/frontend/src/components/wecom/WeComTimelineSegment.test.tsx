import '@testing-library/jest-dom/vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import { beforeEach, expect, it, vi } from 'vitest';
import type { MessageResponse } from '../../api/types';
import type { WeComTimelineBlock } from '../../wecom/segmentWeComTimeline';
import { weComFrameRegistry } from '../../wecom/WeComFrameRegistry';
import { WeComViewerError } from '../../wecom/wecomErrors';
import type { WeComOpenDataFrameOptions } from '../../wecom/wecomSdk';
import { WeComTimelineSegment } from './WeComTimelineSegment';

function wecomMessage(id: string): MessageResponse {
  return {
    id,
    sourceId: `source-${id}`,
    direction: id === '2' ? 'outbound' : 'inbound',
    kind: 'text',
    subject: '',
    bodyText: '',
    bodyHtml: '',
    channelType: 'wecom',
    from: 'sender',
    to: 'receiver',
    occurredAt: `2026-08-17T00:00:0${id}Z`,
    status: 'received',
    ingestSequence: Number(id),
    attachments: [],
  };
}

const segment: WeComTimelineBlock = {
  kind: 'wecom',
  id: 'segment-1',
  items: [wecomMessage('1'), wecomMessage('2')],
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve;
    reject = nextReject;
  });
  return { promise, resolve, reject };
}

beforeEach(() => {
  weComFrameRegistry.reset();
});

it('keeps the frame hidden until handleMounted and destroys it on unmount', async () => {
  let frameOptions: Record<string, unknown> | undefined;
  const frame = { el: document.createElement('iframe'), dispose: vi.fn() };
  const sdk = {
    register: vi.fn(),
    initOpenData: vi.fn(async () => undefined),
    createOpenDataFrameFactory: () => ({
      createOpenDataFrame: vi.fn((options) => {
        frameOptions = options;
        return frame;
      }),
    }),
  };
  const viewer = {
    prepareSegment: vi.fn().mockResolvedValue({
      sdk,
      viewerSessionId: 'session-1',
      viewerAuthToken: 'viewer-token',
      messages: [
        { msgid: 'source-1', secretKey: 'secret-1' },
        { msgid: 'source-2', secretKey: 'secret-2' },
      ],
    }),
    reportComponentError: vi.fn(),
  };

  const { unmount } = render(
    <ConfigProvider>
      <WeComTimelineSegment
        contactPointId="wecom:external-1"
        mode="mixed"
        segment={segment}
        viewer={viewer}
      />
    </ConfigProvider>,
  );

  await waitFor(() => expect(frameOptions).toBeDefined());
  expect(screen.queryByTestId('wecom-frame-visible')).not.toBeInTheDocument();
  expect(screen.getByLabelText('企业微信消息加载中')).toBeVisible();
  expect(screen.getByTestId('wecom-frame-host')).toHaveStyle({
    position: 'relative',
    visibility: 'hidden',
    width: '100%',
  });

  act(() => {
    (frameOptions?.handleMounted as () => void)();
  });
  expect(frame.el).toHaveStyle({ display: 'block', width: '100%', height: '100%' });
  expect(screen.getByTestId('wecom-frame-visible')).toBeVisible();
  expect(screen.queryByLabelText('企业微信消息加载中')).not.toBeInTheDocument();

  unmount();
  expect(frame.dispose).toHaveBeenCalledTimes(1);
});

it('mounts the OpenDataFrame into a dedicated sized frame host', async () => {
  let frameOptions: WeComOpenDataFrameOptions | undefined;
  const viewer = {
    prepareSegment: vi.fn().mockResolvedValue({
      sdk: {
        register: vi.fn(),
        initOpenData: vi.fn(async () => undefined),
        createOpenDataFrameFactory: () => ({
          createOpenDataFrame: vi.fn((options) => {
            frameOptions = options;
            return { el: document.createElement('iframe'), dispose: vi.fn() };
          }),
        }),
      },
      viewerSessionId: 'session-sized-host',
      viewerAuthToken: 'viewer-token',
      messages: [{ msgid: 'source-1', secretKey: 'secret-1' }],
    }),
    reportComponentError: vi.fn(),
  };

  render(
    <ConfigProvider>
      <WeComTimelineSegment
        contactPointId="wecom:external-1"
        mode="standalone"
        segment={segment}
        viewer={viewer}
      />
    </ConfigProvider>,
  );

  await waitFor(() => expect(frameOptions).toBeDefined());
  const frameHost = frameOptions?.el as HTMLElement;
  expect(frameHost).not.toBe(document.querySelector('[data-testid="wecom-frame-host"]'));
  expect(frameHost).toHaveStyle({ position: 'absolute', inset: '0' });
});

it('reports a component error only once for a consumed viewer session', async () => {
  let frameOptions: Record<string, unknown> | undefined;
  const viewer = {
    prepareSegment: vi.fn().mockResolvedValue({
      sdk: {
        register: vi.fn(),
        initOpenData: vi.fn(async () => undefined),
        createOpenDataFrameFactory: () => ({
          createOpenDataFrame: vi.fn((options) => {
            frameOptions = options;
            return { el: document.createElement('iframe'), dispose: vi.fn() };
          }),
        }),
      },
      viewerSessionId: 'session-1',
      viewerAuthToken: 'viewer-token',
      messages: [{ msgid: 'source-1', secretKey: 'secret-1' }],
    }),
    reportComponentError: vi.fn(),
  };

  render(
    <ConfigProvider>
      <WeComTimelineSegment
        contactPointId="wecom:external-1"
        mode="mixed"
        segment={segment}
        viewer={viewer}
      />
    </ConfigProvider>,
  );

  await waitFor(() => expect(frameOptions).toBeDefined());
  act(() => {
    (frameOptions?.error as (error: unknown) => void)(new Error('first component error'));
    (frameOptions?.error as (error: unknown) => void)(new Error('duplicate component error'));
  });

  expect(viewer.reportComponentError).toHaveBeenCalledTimes(1);
  expect(viewer.reportComponentError).toHaveBeenCalledWith('session-1', 'viewer-token');
});

it('aborts a stale contact request and only mounts the latest contact frame', async () => {
  const requestA = deferred<{
    sdk: {
      register: () => void;
      initOpenData: () => Promise<unknown>;
      createOpenDataFrameFactory: () => { createOpenDataFrame: () => { el: HTMLIFrameElement; dispose: () => void } };
    };
    viewerSessionId: string;
    viewerAuthToken: string;
    messages: Array<{ msgid: string; secretKey: string }>;
  }>();
  const frameA = { el: document.createElement('iframe'), dispose: vi.fn() };
  const frameB = { el: document.createElement('iframe'), dispose: vi.fn() };
  const createA = vi.fn(() => frameA);
  const createB = vi.fn(() => frameB);
  let signalA: AbortSignal | undefined;
  const viewer = {
    prepareSegment: vi.fn((contactPointId: string, _messageIds: string[], signal?: AbortSignal) => {
      if (contactPointId === 'wecom:external-a') {
        signalA = signal;
        return requestA.promise;
      }
      return Promise.resolve({
        sdk: { register: vi.fn(), initOpenData: vi.fn(async () => undefined), createOpenDataFrameFactory: () => ({ createOpenDataFrame: createB }) },
        viewerSessionId: 'session-b',
        viewerAuthToken: 'viewer-token',
        messages: [{ msgid: 'source-1', secretKey: 'secret-b' }],
      });
    }),
    reportComponentError: vi.fn(),
  };
  const { rerender } = render(
    <ConfigProvider>
      <WeComTimelineSegment contactPointId="wecom:external-a" mode="mixed" segment={segment} viewer={viewer} />
    </ConfigProvider>,
  );

  await waitFor(() => expect(viewer.prepareSegment).toHaveBeenCalledTimes(1));
  rerender(
    <ConfigProvider>
      <WeComTimelineSegment contactPointId="wecom:external-b" mode="mixed" segment={segment} viewer={viewer} />
    </ConfigProvider>,
  );

  await waitFor(() => expect(viewer.prepareSegment).toHaveBeenCalledTimes(2));
  expect(signalA?.aborted).toBe(true);
  await waitFor(() => expect(createB).toHaveBeenCalledTimes(1));
  requestA.resolve({
    sdk: { register: vi.fn(), initOpenData: vi.fn(async () => undefined), createOpenDataFrameFactory: () => ({ createOpenDataFrame: createA }) },
    viewerSessionId: 'session-a',
    viewerAuthToken: 'viewer-token',
    messages: [{ msgid: 'source-1', secretKey: 'secret-a' }],
  });
  await act(async () => {
    await Promise.resolve();
  });

  expect(createA).not.toHaveBeenCalled();
  expect(createB).toHaveBeenCalledTimes(1);
});

it('keeps the final A frame when the first A request fails after A to B to A', async () => {
  const firstA = deferred<never>();
  const finalA = deferred<{
    sdk: {
      register: () => void;
      initOpenData: () => Promise<unknown>;
      createOpenDataFrameFactory: () => { createOpenDataFrame: (options: WeComOpenDataFrameOptions) => { el: HTMLIFrameElement; dispose: () => void } };
    };
    viewerSessionId: string;
    viewerAuthToken: string;
    messages: Array<{ msgid: string; secretKey: string }>;
  }>();
  const frameA = { el: document.createElement('iframe'), dispose: vi.fn() };
  const frameB = { el: document.createElement('iframe'), dispose: vi.fn() };
  const createA = vi.fn(() => frameA);
  const createB = vi.fn(() => frameB);
  let aRequests = 0;
  const viewer = {
    prepareSegment: vi.fn((contactPointId: string) => {
      if (contactPointId === 'wecom:external-a') {
        aRequests += 1;
        return aRequests === 1 ? firstA.promise : finalA.promise;
      }
      return Promise.resolve({
        sdk: { register: vi.fn(), initOpenData: vi.fn(async () => undefined), createOpenDataFrameFactory: () => ({ createOpenDataFrame: createB }) },
        viewerSessionId: 'session-b',
        viewerAuthToken: 'viewer-token',
        messages: [{ msgid: 'source-1', secretKey: 'secret-b' }],
      });
    }),
    reportComponentError: vi.fn(),
  };
  const { rerender } = render(
    <ConfigProvider>
      <WeComTimelineSegment contactPointId="wecom:external-a" mode="mixed" segment={segment} viewer={viewer} />
    </ConfigProvider>,
  );

  await waitFor(() => expect(viewer.prepareSegment).toHaveBeenCalledTimes(1));
  rerender(
    <ConfigProvider>
      <WeComTimelineSegment contactPointId="wecom:external-b" mode="mixed" segment={segment} viewer={viewer} />
    </ConfigProvider>,
  );
  await waitFor(() => expect(createB).toHaveBeenCalledTimes(1));
  rerender(
    <ConfigProvider>
      <WeComTimelineSegment contactPointId="wecom:external-a" mode="mixed" segment={segment} viewer={viewer} />
    </ConfigProvider>,
  );

  firstA.reject(new WeComViewerError('session-load', '旧 A 请求失败'));
  await waitFor(() => expect(viewer.prepareSegment).toHaveBeenCalledTimes(3));
  finalA.resolve({
    sdk: { register: vi.fn(), initOpenData: vi.fn(async () => undefined), createOpenDataFrameFactory: () => ({ createOpenDataFrame: createA }) },
    viewerSessionId: 'session-a-final',
    viewerAuthToken: 'viewer-token',
    messages: [{ msgid: 'source-1', secretKey: 'secret-a-final' }],
  });
  await waitFor(() => expect(createA).toHaveBeenCalledTimes(1));

  expect(screen.queryByLabelText('企业微信消息加载失败')).not.toBeInTheDocument();
  expect(createB).toHaveBeenCalledTimes(1);
});

it('disposes an old SDK frame without changing the latest contact container', async () => {
  let oldAOptions: WeComOpenDataFrameOptions | undefined;
  let currentBOptions: WeComOpenDataFrameOptions | undefined;
  const frameA = { el: document.createElement('iframe'), dispose: vi.fn() };
  const frameB = { el: document.createElement('iframe'), dispose: vi.fn() };
  const viewer = {
    prepareSegment: vi.fn((contactPointId: string) => Promise.resolve({
      sdk: {
        register: vi.fn(),
        initOpenData: vi.fn(async () => undefined),
        createOpenDataFrameFactory: () => ({
          createOpenDataFrame: (options: WeComOpenDataFrameOptions) => {
            if (contactPointId === 'wecom:external-a') oldAOptions = options;
            else currentBOptions = options;
            return contactPointId === 'wecom:external-a' ? frameA : frameB;
          },
        }),
      },
      viewerSessionId: `session-${contactPointId}`,
      viewerAuthToken: 'viewer-token',
      messages: [{ msgid: 'source-1', secretKey: 'secret' }],
    })),
    reportComponentError: vi.fn(),
  };
  const { rerender } = render(
    <ConfigProvider>
      <WeComTimelineSegment contactPointId="wecom:external-a" mode="mixed" segment={segment} viewer={viewer} />
    </ConfigProvider>,
  );

  await waitFor(() => expect(oldAOptions).toBeDefined());
  rerender(
    <ConfigProvider>
      <WeComTimelineSegment contactPointId="wecom:external-b" mode="mixed" segment={segment} viewer={viewer} />
    </ConfigProvider>,
  );
  await waitFor(() => expect(currentBOptions).toBeDefined());
  act(() => {
    (oldAOptions?.handleMounted as () => void)();
    (currentBOptions?.handleMounted as () => void)();
  });

  expect(frameA.dispose).toHaveBeenCalled();
  expect(frameB.dispose).not.toHaveBeenCalled();
  expect(screen.getByTestId('wecom-frame-visible')).toBeVisible();
});

it('shows a compact retry marker when the first render fails', async () => {
  const viewer = {
    prepareSegment: vi.fn().mockRejectedValue(new WeComViewerError('session-load', '会话引用读取失败', 503, 'WECOM_VIEWER_UNAVAILABLE')),
    reportComponentError: vi.fn(),
  };

  render(
    <ConfigProvider>
      <WeComTimelineSegment
        contactPointId="wecom:external-1"
        mode="mixed"
        segment={segment}
        viewer={viewer}
      />
    </ConfigProvider>,
  );

  expect(await screen.findByLabelText('企业微信消息加载失败')).toBeVisible();
  expect(screen.getByText('企业微信消息加载失败：session-load：会话引用读取失败（503，WECOM_VIEWER_UNAVAILABLE）')).toBeVisible();
  expect(screen.getByRole('button', { name: '重新加载企业微信消息' })).toBeVisible();
});

it('does not remain blank when the OpenDataFrame never reports mounted', async () => {
  vi.useFakeTimers();
  const viewer = {
    prepareSegment: vi.fn().mockResolvedValue({
      sdk: {
        register: vi.fn(),
        initOpenData: vi.fn(async () => undefined),
        createOpenDataFrameFactory: () => ({
          createOpenDataFrame: vi.fn(() => ({ el: document.createElement('iframe'), dispose: vi.fn() })),
        }),
      },
      viewerSessionId: 'session-1',
      viewerAuthToken: 'viewer-token',
      messages: [{ msgid: 'source-1', secretKey: 'secret-1' }],
    }),
    reportComponentError: vi.fn(),
  };

  try {
    render(
      <ConfigProvider>
        <WeComTimelineSegment
          contactPointId="wecom:external-1"
          mode="mixed"
          segment={segment}
          viewer={viewer}
        />
      </ConfigProvider>,
    );

    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(viewer.prepareSegment).toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(15_000);
    });
    expect(screen.getByText(/企业微信消息加载失败：frame-mount：企业微信组件挂载超时/)).toBeVisible();
  } finally {
    vi.useRealTimers();
  }
});

it('blocks an invalid component modal URL instead of allowing the SDK to navigate the app', async () => {
  let frameOptions: Record<string, unknown> | undefined;
  const viewer = {
    prepareSegment: vi.fn().mockResolvedValue({
      sdk: {
        register: vi.fn(),
        initOpenData: vi.fn(async () => undefined),
        createOpenDataFrameFactory: () => ({
          createOpenDataFrame: vi.fn((options) => {
            frameOptions = options;
            return { el: document.createElement('iframe'), dispose: vi.fn() };
          }),
        }),
      },
      viewerSessionId: 'session-1',
      viewerAuthToken: 'viewer-token',
      messages: [
        { msgid: 'source-1', secretKey: 'secret-1' },
        { msgid: 'source-2', secretKey: 'secret-2' },
      ],
    }),
    reportComponentError: vi.fn(),
  };

  render(
    <ConfigProvider>
      <WeComTimelineSegment
        contactPointId="wecom:external-1"
        mode="standalone"
        segment={segment}
        viewer={viewer}
      />
    </ConfigProvider>,
  );

  await waitFor(() => expect(frameOptions).toBeDefined());
  const handled = (frameOptions?.handleModal as (payload: { modalUrl: string }) => boolean)({
    modalUrl: 'javascript:alert(1)',
  });

  expect(handled).toBe(false);
  expect(viewer.reportComponentError).toHaveBeenCalledWith('session-1', 'viewer-token');
});

it('renders a trusted message detail in a sandbox that cannot navigate the CRM page', async () => {
  let frameOptions: Record<string, unknown> | undefined;
  const viewer = {
    prepareSegment: vi.fn().mockResolvedValue({
      sdk: {
        register: vi.fn(),
        initOpenData: vi.fn(async () => undefined),
        createOpenDataFrameFactory: () => ({
          createOpenDataFrame: vi.fn((options) => {
            frameOptions = options;
            return { el: document.createElement('iframe'), dispose: vi.fn() };
          }),
        }),
      },
      viewerSessionId: 'session-1',
      viewerAuthToken: 'viewer-token',
      messages: [{ msgid: 'source-1', secretKey: 'secret-1' }],
    }),
    reportComponentError: vi.fn(),
  };

  render(
    <ConfigProvider>
      <WeComTimelineSegment
        contactPointId="wecom:external-1"
        mode="standalone"
        segment={segment}
        viewer={viewer}
      />
    </ConfigProvider>,
  );

  await waitFor(() => expect(frameOptions).toBeDefined());
  act(() => {
    (frameOptions?.handleModal as (payload: { modalUrl: string; modalSize: { width: number; height: number } }) => boolean)({
      modalUrl: 'https://open.work.weixin.qq.com/viewer/message',
      modalSize: { width: 640, height: 480 },
    });
  });

  expect(screen.getByTitle('企业微信会话详情')).toHaveAttribute(
    'sandbox',
    'allow-scripts allow-same-origin allow-forms allow-popups',
  );
});

it('does not render a continuous-message footer in standalone mode', async () => {
  let frameOptions: Record<string, unknown> | undefined;
  const viewer = {
    prepareSegment: vi.fn().mockResolvedValue({
      sdk: {
        register: vi.fn(),
        initOpenData: vi.fn(async () => undefined),
        createOpenDataFrameFactory: () => ({
          createOpenDataFrame: vi.fn((options) => {
            frameOptions = options;
            return { el: document.createElement('iframe'), dispose: vi.fn() };
          }),
        }),
      },
      viewerSessionId: 'session-1',
      viewerAuthToken: 'viewer-token',
      messages: [{ msgid: 'source-1', secretKey: 'secret-1' }],
    }),
    reportComponentError: vi.fn(),
  };

  render(
    <ConfigProvider>
      <WeComTimelineSegment
        contactPointId="wecom:external-1"
        mode="standalone"
        segment={segment}
        viewer={viewer}
      />
    </ConfigProvider>,
  );

  await waitFor(() => expect(frameOptions).toBeDefined());
  act(() => {
    (frameOptions?.handleMounted as () => void)();
  });
  const host = screen.getByTestId('wecom-frame-visible');
  const wrapper = host.parentElement;
  const segmentRoot = wrapper?.parentElement;
  expect(segmentRoot).toHaveStyle({ width: '100%', height: '100%', minHeight: '0', minWidth: '0' });
  expect(wrapper).toHaveStyle({ width: '100%', height: '100%', minHeight: '0', minWidth: '0' });
  expect(host).toHaveStyle({ width: '100%', height: '100%', minHeight: '0', minWidth: '0', overflow: 'hidden' });
  expect(screen.queryByText(/连续消息/)).not.toBeInTheDocument();
});
