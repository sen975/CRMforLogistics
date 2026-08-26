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
