import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  bootstrapWeComViewer,
  createWeComViewerSession,
  fetchWeComBinding,
  fetchWeComJsSdkConfig,
  fetchWeComViewerSession,
} from '../api/endpoints';
import { useWeComViewer } from './useWeComViewer';

vi.mock('../api/endpoints', () => ({
  bootstrapWeComViewer: vi.fn(),
  createWeComViewerSession: vi.fn(),
  fetchWeComBinding: vi.fn(),
  fetchWeComJsSdkConfig: vi.fn(),
  fetchWeComViewerSession: vi.fn(),
  recordWeComViewerEvent: vi.fn(),
}));

vi.mock('./useAuth', () => ({
  useAuth: () => ({ token: 'crm-token', wecomViewerAuthToken: 'viewer-token' }),
}));

vi.mock('../wecom/wecomSdk', () => ({
  loadWeComViewerSdk: vi.fn(async () => ({
    register: vi.fn(),
    initOpenData: vi.fn(async () => undefined),
    createOpenDataFrameFactory: vi.fn(),
  })),
}));

const mockedCreateSession = vi.mocked(createWeComViewerSession);
const mockedFetchSession = vi.mocked(fetchWeComViewerSession);
const mockedFetchConfig = vi.mocked(fetchWeComJsSdkConfig);

beforeEach(() => {
  vi.clearAllMocks();
  mockedFetchConfig.mockResolvedValue({
    corpId: 'corp-id',
    agentId: 'agent-id',
    jsApiList: ['openDataFrame'],
    configSignature: { timestamp: '1', nonceStr: 'nonce', signature: 'signature' },
    agentConfigSignature: { timestamp: '1', nonceStr: 'agent-nonce', signature: 'agent-signature' },
  });
});

function detail(sessionId: string, ids: string[]) {
  return {
    viewerSessionId: sessionId,
    corpId: 'corp-id',
    agentId: 'agent-id',
    messages: ids.map((msgid) => ({ msgid, secretKey: `secret-${msgid}` })),
  };
}

describe('useWeComViewer message preparation', () => {
  it('全部命中缓存时不创建新的 session', async () => {
    mockedCreateSession.mockResolvedValueOnce({ viewerSessionId: 'session-1', expiresIn: 300 });
    mockedFetchSession.mockResolvedValueOnce(detail('session-1', ['m1']));

    const { result } = renderHook(() => useWeComViewer());
    await act(async () => {
      await result.current.prepareSegment('wecom:contact-a', ['m1']);
    });
    await act(async () => {
      await result.current.prepareSegment('wecom:contact-a', ['m1']);
    });

    expect(mockedCreateSession).toHaveBeenCalledTimes(1);
    expect(mockedFetchSession).toHaveBeenCalledTimes(1);
  });

  it('cache miss 按 15 条以内分批读取并按输入顺序合并', async () => {
    const ids = Array.from({ length: 16 }, (_, index) => `m${index + 1}`);
    mockedCreateSession
      .mockResolvedValueOnce({ viewerSessionId: 'session-1', expiresIn: 300 })
      .mockResolvedValueOnce({ viewerSessionId: 'session-2', expiresIn: 300 });
    mockedFetchSession
      .mockResolvedValueOnce(detail('session-1', ids.slice(0, 15)))
      .mockResolvedValueOnce(detail('session-2', ids.slice(15)));

    const { result } = renderHook(() => useWeComViewer());
    let prepared: Awaited<ReturnType<typeof result.current.prepareSegment>> | undefined;
    await act(async () => {
      prepared = await result.current.prepareSegment('wecom:contact-a', ids);
    });

    expect(mockedCreateSession.mock.calls.map(([contact, messageIds]) => [contact, messageIds])).toEqual([
      ['wecom:contact-a', ids.slice(0, 15)],
      ['wecom:contact-a', ids.slice(15)],
    ]);
    expect(prepared?.messages.map((message) => message.msgid)).toEqual(ids);
  });
});
