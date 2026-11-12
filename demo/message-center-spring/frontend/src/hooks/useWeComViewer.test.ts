import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  bootstrapWeComViewer,
  createWeComViewerSession,
  fetchWeComBinding,
  fetchWeComJsSdkConfig,
  fetchWeComViewerSession,
  recordWeComViewerEvent,
} from '../api/endpoints';
import { useWeComViewer } from './useWeComViewer';
import { loadWeComViewerSdk } from '../wecom/wecomSdk';

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
const mockedRecordEvent = vi.mocked(recordWeComViewerEvent);
const mockedLoadSdk = vi.mocked(loadWeComViewerSdk);

beforeEach(() => {
  vi.clearAllMocks();
  window.history.replaceState({}, '', '/');
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
  it('路由 URL 变化后按新 URL 重新获取 SDK 签名', async () => {
    mockedCreateSession.mockResolvedValueOnce({ viewerSessionId: 'session-1', expiresIn: 300 });
    mockedFetchSession.mockResolvedValueOnce(detail('session-1', ['m1']));
    mockedFetchConfig
      .mockResolvedValueOnce({
        corpId: 'corp-id',
        agentId: 'agent-id',
        jsApiList: ['openDataFrame'],
        configSignature: { timestamp: '1', nonceStr: 'nonce-a', signature: 'signature-a' },
        agentConfigSignature: { timestamp: '1', nonceStr: 'agent-nonce-a', signature: 'agent-signature-a' },
      })
      .mockResolvedValueOnce({
        corpId: 'corp-id',
        agentId: 'agent-id',
        jsApiList: ['openDataFrame'],
        configSignature: { timestamp: '2', nonceStr: 'nonce-b', signature: 'signature-b' },
        agentConfigSignature: { timestamp: '2', nonceStr: 'agent-nonce-b', signature: 'agent-signature-b' },
      });

    const { result } = renderHook(() => useWeComViewer());
    await act(async () => {
      await result.current.prepareSegment('wecom:contact-a', ['m1']);
    });

    const sdk = await mockedLoadSdk.mock.results[0]?.value;
    const register = (sdk.register as ReturnType<typeof vi.fn>);
    const options = register.mock.calls[0]?.[0];
    expect(options).toBeTruthy();
    expect(mockedFetchConfig).toHaveBeenCalledWith('http://localhost:3000/', 'viewer-token');

    window.history.pushState({}, '', '/thread/contact-b');
    await expect(options.getConfigSignature('http://localhost:3000/thread/contact-b'))
      .resolves.toEqual({ timestamp: '2', nonceStr: 'nonce-b', signature: 'signature-b' });
    await expect(options.getAgentConfigSignature('http://localhost:3000/thread/contact-b'))
      .resolves.toEqual({ timestamp: '2', nonceStr: 'agent-nonce-b', signature: 'agent-signature-b' });
    expect(mockedFetchConfig).toHaveBeenLastCalledWith('http://localhost:3000/thread/contact-b', 'viewer-token');
  });

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

  it('同一 generation 的组件错误只上报一次且保留原始分类', async () => {
    mockedRecordEvent.mockRejectedValue(new Error('403'));
    const { result } = renderHook(() => useWeComViewer());
    await act(async () => {
      await result.current.reportComponentError('session-1', 'viewer-token', {
        stage: 'frame-update', generation: 7, errorCategory: 'SDK_RESULT_FAILURE',
      });
      await result.current.reportComponentError('session-1', 'viewer-token', {
        stage: 'frame-update', generation: 7, errorCategory: 'SDK_RESULT_FAILURE',
      });
    });
    expect(mockedRecordEvent).toHaveBeenCalledTimes(1);
    expect(mockedRecordEvent).toHaveBeenCalledWith({
      eventKey: '7:session-1:frame-update:SDK_RESULT_FAILURE',
      stage: 'frame-update',
      generation: 7,
      viewerSessionId: 'session-1',
      errorCategory: 'SDK_RESULT_FAILURE',
    }, 'viewer-token');
  });
});
