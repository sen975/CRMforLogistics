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

function groupMessage(
  id: string,
  displayName: string,
  avatarUrl?: string | null,
  overrides?: Partial<NonNullable<MessageResponse['sender']>>,
): MessageResponse {
  return {
    ...message(id),
    conversationType: 'GROUP',
    occurredAt: '2026-08-17T00:00:00Z',
    sender: {
      partyId: `party-${id}`,
      partyType: 'EMPLOYEE',
      providerPartyId: `provider-${id}`,
      displayName,
      avatarUrl,
      contactId: null,
      contactAccessible: false,
      isCurrentViewer: false,
      ...overrides,
    },
  };
}

function renderedMessage(id: string) {
  return {
    msgid: `source-${id}`,
    secretKey: `secret-${id}`,
    direction: 'inbound',
    senderAvatarUrl: '',
    senderAvatarText: '未',
    senderAvatarKind: 'unknown',
    occurredAt: '2026-08-17 08:00',
  };
}

function renderedFrameData(id: string) {
  return {
    showGroupHeader: false,
    groupChatId: '',
    showMessageMetadata: true,
    showSenderName: false,
    showDirectTime: true,
    msgList: [renderedMessage(id)],
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
  it('projects direct-message avatar and time without a sender name into the single frame', async () => {
    const harness = sdkHarness();
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn().mockResolvedValue(prepared(harness.sdk, 'a')),
      reportComponentError: vi.fn(),
    };
    const item = {
      ...message('a'),
      sender: {
        partyId: 'party-a',
        partyType: 'EXTERNAL_CONTACT',
        providerPartyId: 'provider-a',
        displayName: '客户 A',
        avatarUrl: 'https://example.com/customer-a.png',
        contactId: null,
        contactAccessible: false,
        isCurrentViewer: false,
      },
    };

    render(
      <ConfigProvider>
        <WeComConversationFrame contactPointId="wecom:contact-a" items={[item]} viewer={viewer} />
      </ConfigProvider>,
    );

    await waitFor(() => expect(harness.createOpenDataFrame).toHaveBeenCalledTimes(1));
    const frame = harness.createOpenDataFrame.mock.results[0]?.value;
    await waitFor(() => expect(frame.setData).toHaveBeenCalledWith({
      showGroupHeader: false,
      groupChatId: '',
      showMessageMetadata: true,
      showSenderName: false,
      showDirectTime: true,
      msgList: [{
        msgid: 'source-a',
        secretKey: 'secret-a',
        direction: 'inbound',
        senderAvatarUrl: 'https://example.com/customer-a.png',
        senderAvatarText: '客',
        senderAvatarKind: 'external',
        occurredAt: '2026-08-17 08:00',
      }],
    }));
    const options = harness.createOpenDataFrame.mock.calls[0]?.[0] as any;
    expect(options.template).toContain('wx:if="{{data.showSenderName}}"');
    expect(JSON.stringify(frame.setData.mock.calls)).not.toContain('客户 A');
  });

  it('scrolls the frame to the latest message after filling a conversation', async () => {
    const harness = sdkHarness();
    const scrollTo = vi.fn();
    (harness.sdk as any).createScrollViewContext = vi.fn().mockResolvedValue({ scrollTo });
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn().mockResolvedValue(prepared(harness.sdk, 'a')),
      reportComponentError: vi.fn(),
    };

    render(
      <ConfigProvider>
        <WeComConversationFrame contactPointId="wecom:contact-a" items={[message('a')]} viewer={viewer} />
      </ConfigProvider>,
    );

    await waitFor(() => expect(scrollTo).toHaveBeenCalledWith({ top: Number.MAX_SAFE_INTEGER }));
  });

  it('projects group sender name, avatar and time into the single frame', async () => {
    const harness = sdkHarness();
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn(),
      prepareTargetSegment: vi.fn().mockResolvedValue(prepared(harness.sdk, 'a')),
      reportComponentError: vi.fn(),
    };

    render(
      <ConfigProvider>
        <WeComConversationFrame
          contactPointId="wecom:group-a"
          target={{ targetType: 'WECOM_GROUP', targetId: 'group-a' }}
          items={[groupMessage('a', '员工 A', 'https://example.com/a.png')]}
          viewer={viewer}
        />
      </ConfigProvider>,
    );

    await waitFor(() => expect(harness.createOpenDataFrame).toHaveBeenCalledTimes(1));
    const options = harness.createOpenDataFrame.mock.calls[0]?.[0] as any;
    const frame = harness.createOpenDataFrame.mock.results[0]?.value;
    await waitFor(() => expect(frame.setData).toHaveBeenCalled());

    expect(options.template).toContain('{{item.senderDisplayName}}');
    expect(options.template).toContain('{{item.senderAvatarUrl}}');
    expect(options.template).toContain('{{item.occurredAt}}');
    expect(options.template).not.toContain('providerPartyId');
    expect(options.template).toContain('<view class="wecom-meta">');
    expect(options.template).toContain('<span wx:if="{{data.showSenderName}}" class="wecom-sender">{{item.senderDisplayName}}</span>');
    expect(options.template).toContain('<span class="wecom-time">{{item.occurredAt}}</span>');
    expect(options.template).not.toContain('<text');
    expect(options.template).toContain('wx:if="{{data.showSenderName}}"');
    expect(frame.setData).toHaveBeenCalledWith({
      showGroupHeader: false,
      groupChatId: '',
      showMessageMetadata: true,
      showSenderName: true,
      showDirectTime: false,
      msgList: [{
        msgid: 'source-a',
        secretKey: 'secret-a',
        direction: 'inbound',
        senderDisplayName: '员工 A',
        senderAvatarUrl: 'https://example.com/a.png',
        senderAvatarText: '员',
        senderAvatarKind: 'employee',
        occurredAt: '2026-08-17 08:00',
      }],
    });
  });

  it('keeps the real group name in the same fixed frame header while only messages scroll', async () => {
    const harness = sdkHarness();
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn(),
      prepareTargetSegment: vi.fn().mockResolvedValue(prepared(harness.sdk, 'a')),
      reportComponentError: vi.fn(),
    };

    render(
      <ConfigProvider>
        <WeComConversationFrame
          contactPointId="wecom:group-a"
          target={{ targetType: 'WECOM_GROUP', targetId: 'group-a', chatId: 'wrNTkcAAAJuUdnO3GEr6qeCT' } as any}
          items={[groupMessage('a', '员工 A')]}
          viewer={viewer}
        />
      </ConfigProvider>,
    );

    await waitFor(() => expect(harness.createOpenDataFrame).toHaveBeenCalledTimes(1));
    const options = harness.createOpenDataFrame.mock.calls[0]?.[0] as any;
    const frame = harness.createOpenDataFrame.mock.results[0]?.value;

    expect(options.template).toContain('<ww-open-data type="chatName" openid="{{data.groupChatId}}"');
    expect(options.template).toContain('class="wecom-group-header"');
    expect(options.template).toContain('<scroll-view ref="message-scroll" scroll-y="true" class="wecom-scroll">');
    await waitFor(() => expect(frame.setData).toHaveBeenCalledWith(expect.objectContaining({
      showGroupHeader: true,
      groupChatId: 'wrNTkcAAAJuUdnO3GEr6qeCT',
    })));
  });

  it('uses explicit group sender fallbacks without exposing provider ids', async () => {
    const harness = sdkHarness();
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn(),
      prepareTargetSegment: vi.fn().mockResolvedValue(prepared(harness.sdk, 'a')),
      reportComponentError: vi.fn(),
    };

    render(
      <ConfigProvider>
        <WeComConversationFrame
          contactPointId="wecom:group-a"
          target={{ targetType: 'WECOM_GROUP', targetId: 'group-a' }}
          items={[groupMessage('a', '', null)]}
          viewer={viewer}
        />
      </ConfigProvider>,
    );

    await waitFor(() => expect(harness.createOpenDataFrame).toHaveBeenCalledTimes(1));
    const frame = harness.createOpenDataFrame.mock.results[0]?.value;
    await waitFor(() => expect(frame.setData).toHaveBeenCalledWith(expect.objectContaining({
      msgList: [expect.objectContaining({
        senderDisplayName: '群成员 1',
        senderAvatarUrl: '',
        senderAvatarText: '未',
        senderAvatarKind: 'employee',
      })],
    })));
    expect(JSON.stringify(frame.setData.mock.calls)).not.toContain('provider-a');
  });

  it('assigns stable distinct fallback names to different unnamed group senders', async () => {
    const harness = sdkHarness();
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn(),
      prepareTargetSegment: vi.fn().mockResolvedValue({
        ...prepared(harness.sdk, 'a'),
        messages: [
          { msgid: 'source-a', secretKey: 'secret-a' },
          { msgid: 'source-b', secretKey: 'secret-b' },
          { msgid: 'source-c', secretKey: 'secret-c' },
          { msgid: 'source-d', secretKey: 'secret-d' },
        ],
      }),
      reportComponentError: vi.fn(),
    };
    const items = [
      groupMessage('a', '', null, { providerPartyId: 'provider-a', partyType: 'EMPLOYEE' }),
      groupMessage('b', '', null, { providerPartyId: 'provider-b', partyType: 'EXTERNAL_CONTACT' }),
      groupMessage('c', '', null, { providerPartyId: 'provider-a', partyType: 'EMPLOYEE' }),
      groupMessage('d', '', null, { providerPartyId: 'provider-b', partyType: 'EXTERNAL_CONTACT' }),
    ];

    render(
      <ConfigProvider>
        <WeComConversationFrame
          contactPointId="wecom:group-a"
          target={{ targetType: 'WECOM_GROUP', targetId: 'group-a' }}
          items={items}
          viewer={viewer}
        />
      </ConfigProvider>,
    );

    await waitFor(() => expect(harness.createOpenDataFrame).toHaveBeenCalledTimes(1));
    const frame = harness.createOpenDataFrame.mock.results[0]?.value;
    await waitFor(() => expect(frame.setData).toHaveBeenCalledWith(expect.objectContaining({
      msgList: [
        expect.objectContaining({ senderDisplayName: '群成员 1', occurredAt: '2026-08-17 08:00' }),
        expect.objectContaining({ senderDisplayName: '群成员 2', occurredAt: '2026-08-17 08:00' }),
        expect.objectContaining({ senderDisplayName: '群成员 1', occurredAt: '2026-08-17 08:00' }),
        expect.objectContaining({ senderDisplayName: '群成员 2', occurredAt: '2026-08-17 08:00' }),
      ],
    })));
    expect(JSON.stringify(frame.setData.mock.calls)).not.toContain('provider-a');
    expect(JSON.stringify(frame.setData.mock.calls)).not.toContain('provider-b');
  });

  it('keeps a timestamp on every alternating group message', async () => {
    const harness = sdkHarness();
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn(),
      prepareTargetSegment: vi.fn().mockResolvedValue({
        ...prepared(harness.sdk, 'a'),
        messages: [
          { msgid: 'source-a', secretKey: 'secret-a' },
          { msgid: 'source-b', secretKey: 'secret-b' },
        ],
      }),
      reportComponentError: vi.fn(),
    };
    const first = groupMessage('a', '甲', null, { providerPartyId: 'provider-a' });
    const second = groupMessage('b', '乙', null, { providerPartyId: 'provider-b' });
    first.occurredAt = '2026-08-17T01:02:00Z';
    second.occurredAt = '2026-08-17T03:04:00Z';

    render(
      <ConfigProvider>
        <WeComConversationFrame
          contactPointId="wecom:group-a"
          target={{ targetType: 'WECOM_GROUP', targetId: 'group-a' }}
          items={[first, second]}
          viewer={viewer}
        />
      </ConfigProvider>,
    );

    await waitFor(() => expect(harness.createOpenDataFrame).toHaveBeenCalledTimes(1));
    const frame = harness.createOpenDataFrame.mock.results[0]?.value;
    await waitFor(() => expect(frame.setData).toHaveBeenCalledWith(expect.objectContaining({
      msgList: [
        expect.objectContaining({ occurredAt: '2026-08-17 09:02' }),
        expect.objectContaining({ occurredAt: '2026-08-17 11:04' }),
      ],
    })));
  });

  it('keeps group metadata when the viewer returns normalized message ids', async () => {
    const harness = sdkHarness();
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn(),
      prepareTargetSegment: vi.fn().mockResolvedValue({
        ...prepared(harness.sdk, 'a'),
        messages: [
          { msgid: 'viewer-a', secretKey: 'secret-a' },
          { msgid: 'viewer-b', secretKey: 'secret-b' },
        ],
      }),
      reportComponentError: vi.fn(),
    };
    const first = groupMessage('a', '甲', null);
    const second = groupMessage('b', '乙', null);

    render(
      <ConfigProvider>
        <WeComConversationFrame
          contactPointId="wecom:group-a"
          target={{ targetType: 'WECOM_GROUP', targetId: 'group-a' }}
          items={[first, second]}
          viewer={viewer}
        />
      </ConfigProvider>,
    );

    await waitFor(() => expect(harness.createOpenDataFrame).toHaveBeenCalledTimes(1));
    const frame = harness.createOpenDataFrame.mock.results[0]?.value;
    await waitFor(() => expect(frame.setData).toHaveBeenCalledWith(expect.objectContaining({
      msgList: [
        expect.objectContaining({ senderDisplayName: '甲', msgid: 'viewer-a' }),
        expect.objectContaining({ senderDisplayName: '乙', msgid: 'viewer-b' }),
      ],
    })));
  });

  it('does not positionally mislabel metadata when the viewer returns a partial result', async () => {
    const harness = sdkHarness();
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn(),
      prepareTargetSegment: vi.fn().mockResolvedValue({
        ...prepared(harness.sdk, 'a'),
        messages: [{ msgid: 'viewer-b', secretKey: 'secret-b' }],
      }),
      reportComponentError: vi.fn(),
    };
    const first = groupMessage('a', '甲', null);
    const second = groupMessage('b', '乙', null);

    render(
      <ConfigProvider>
        <WeComConversationFrame
          contactPointId="wecom:group-a"
          target={{ targetType: 'WECOM_GROUP', targetId: 'group-a' }}
          items={[first, second]}
          viewer={viewer}
        />
      </ConfigProvider>,
    );

    await waitFor(() => expect(harness.createOpenDataFrame).toHaveBeenCalledTimes(1));
    const frame = harness.createOpenDataFrame.mock.results[0]?.value;
    await waitFor(() => expect(frame.setData).toHaveBeenCalledWith(expect.objectContaining({
      msgList: [expect.objectContaining({ msgid: 'viewer-b', senderDisplayName: '未获取昵称' })],
    })));
  });

  it('stretches the official frame element across the full timeline host', async () => {
    const harness = sdkHarness();
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn().mockResolvedValue(prepared(harness.sdk, 'a')),
      reportComponentError: vi.fn(),
    };

    render(
      <ConfigProvider>
        <WeComConversationFrame contactPointId="wecom:contact-a" items={[message('a')]} viewer={viewer} />
      </ConfigProvider>,
    );

    await waitFor(() => expect(harness.createOpenDataFrame).toHaveBeenCalledTimes(1));
    const frame = harness.createOpenDataFrame.mock.results[0]?.value;
    expect(frame.el).toHaveStyle({
      display: 'block',
      width: '100%',
      height: '100%',
    });
    expect(frame.el.style.minWidth).toBe('0px');
    expect(frame.el.style.minHeight).toBe('0px');
    expect(frame.el.style.border).toBe('0px');
  });

  it('keeps one frame when the previous setData is still pending', async () => {
    const harness = sdkHarness();
    let firstSetData = true;
    let resolveFirstSetData!: () => void;
    harness.createOpenDataFrame.mockImplementation((options: any) => {
      const frame = {
        el: document.createElement('iframe'),
        data: options.data,
        setData: vi.fn((partialData: Record<string, unknown>) => {
          if (firstSetData) {
            firstSetData = false;
            return new Promise<void>((resolve) => {
              resolveFirstSetData = () => {
                Object.assign(frame.data, partialData);
                options.handleUpdated?.();
                resolve();
              };
            });
          }
          Object.assign(frame.data, partialData);
          options.handleUpdated?.();
          return Promise.resolve();
        }),
        dispose: vi.fn(),
      };
      queueMicrotask(() => options.handleMounted?.());
      return frame;
    });
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

    resolveFirstSetData();
    await waitFor(() => expect(screen.getByTestId('wecom-conversation-frame-visible')).toBeVisible());
    const firstFrame = harness.createOpenDataFrame.mock.results[0]?.value;
    await waitFor(() => expect(firstFrame.setData).toHaveBeenCalledWith(renderedFrameData('b')));
    await waitFor(() => expect(firstFrame.setData.mock.calls.at(-1)?.[0]).toEqual(renderedFrameData('b')));
    expect(harness.createOpenDataFrame).toHaveBeenCalledTimes(1);
    expect(firstFrame.dispose).not.toHaveBeenCalled();
    expect(screen.getByTestId('wecom-conversation-frame-visible')).toBeVisible();
  });

  it('preserves the single-frame clear and reload strategy while switching A to B', async () => {
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
    const frame = harness.createOpenDataFrame.mock.results[0]?.value;

    rerender(
      <ConfigProvider>
        <WeComConversationFrame contactPointId="wecom:contact-b" items={[message('b')]} viewer={viewer} />
      </ConfigProvider>,
    );
    await waitFor(() => expect(frame.setData).toHaveBeenCalledWith(renderedFrameData('b')));

    expect(frame.setData.mock.calls.some(([data]: [{ msgList?: unknown[] }]) =>
      Array.isArray(data.msgList) && data.msgList.length === 0)).toBe(true);
  });

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
    expect(frame.setData).toHaveBeenCalledWith(renderedFrameData('b'));
    expect(screen.getByTestId('wecom-conversation-frame-visible')).toBeVisible();
  });

  it('binds message component errors to the frame error boundary', async () => {
    const harness = sdkHarness();
    const viewer: WeComViewerHandle = {
      prepareSegment: vi.fn().mockResolvedValue(prepared(harness.sdk, 'a')),
      reportComponentError: vi.fn(),
    };

    render(
      <ConfigProvider>
        <WeComConversationFrame contactPointId="wecom:contact-a" items={[message('a')]} viewer={viewer} />
      </ConfigProvider>,
    );

    await waitFor(() => expect(harness.createOpenDataFrame).toHaveBeenCalledTimes(1));
    const options = harness.createOpenDataFrame.mock.calls[0]?.[0] as any;
    expect(options.template).toContain('binderror="handleMessageError"');
    expect(options.methods?.handleMessageError).toEqual(expect.any(Function));

    options.methods.handleMessageError(new Error('message component failed'));
    expect(await screen.findByLabelText('企业微信消息加载失败')).toHaveTextContent(
      'frame-update：message component failed',
    );
  });

  it('waits for clear update completion before filling the next contact in the same frame', async () => {
    const harness = sdkHarness();
    let resolveUpdate!: () => void;
    let updateCount = 0;
    harness.createOpenDataFrame.mockImplementation((options: any) => {
      const frame = {
        el: document.createElement('iframe'),
        data: options.data,
        setData: vi.fn((partialData: Record<string, unknown>) => {
          updateCount += 1;
          if (updateCount === 2) {
            resolveUpdate = () => {
              Object.assign(frame.data, partialData);
              options.handleUpdated?.();
            };
            return Promise.resolve();
          }
          Object.assign(frame.data, partialData);
          options.handleUpdated?.();
          return Promise.resolve();
        }),
        dispose: vi.fn(),
      };
      queueMicrotask(() => options.handleMounted?.());
      return frame;
    });
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
    await waitFor(() => expect(updateCount).toBe(1));

    rerender(
      <ConfigProvider>
        <WeComConversationFrame contactPointId="wecom:contact-b" items={[message('b')]} viewer={viewer} />
      </ConfigProvider>,
    );

    await waitFor(() => expect(updateCount).toBe(2));
    expect(updateCount).toBe(2);
    resolveUpdate();
    await waitFor(() => expect(updateCount).toBe(3));
    const frame = harness.createOpenDataFrame.mock.results[0]?.value;
    expect(frame.setData.mock.calls[2]?.[0]).toEqual(renderedFrameData('b'));
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
    expect(frame.setData).not.toHaveBeenCalledWith(renderedFrameData('a'));
    expect(frame.setData).toHaveBeenCalledWith(renderedFrameData('b'));
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
