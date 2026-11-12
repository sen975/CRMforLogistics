import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ASSISTANT_MESSAGES_URL,
  AssistantFrameReader,
  AssistantStreamFailure,
  streamAssistantMessage,
} from './assistantStream';
import type {
  AssistantDeltaFrame,
  AssistantMessageRequest,
  AssistantStreamFrame,
} from './types';

/** 服务端写一帧的样子：`data: {...}` + 一个空行。 */
const frame = (body: unknown) => `data: ${JSON.stringify(body)}\n\n`;

const ANSWER = { type: 'final', kind: 'ANSWER', message: '张总那边我看过了' };

let warn: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  window.localStorage.clear();
  // 这一层刻意会 warn（半帧、不认识的帧、连接断）。用例关心的是**行为**，
  // 留痕本身在需要它的地方另有断言。
  warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  warn.mockRestore();
  vi.unstubAllGlobals();
});

describe('AssistantFrameReader：把字节流切成帧', () => {
  it('reassembles a frame no matter where the network cuts the bytes', () => {
    // 一字节一块是最苛刻的切法：一个汉字三个字节，所以每一块都可能切在一个字的中间。
    // 这里同时钉住两件事 —— 排版缓冲（一帧被切成两半）与解码缓冲（一个字符被切成两半）。
    const payload = frame({ type: 'delta', text: '张总' }) + frame(ANSWER);
    const bytes = new TextEncoder().encode(payload);
    const decoder = new TextDecoder();
    const reader = new AssistantFrameReader();

    const frames: AssistantStreamFrame[] = [];
    for (const byte of bytes) {
      frames.push(...reader.push(decoder.decode(new Uint8Array([byte]), { stream: true })));
    }

    expect(frames.map((one) => one.type)).toEqual(['delta', 'final']);
    expect((frames[0] as AssistantDeltaFrame).text).toBe('张总');
  });

  it('takes several frames out of one chunk', () => {
    const reader = new AssistantFrameReader();

    const frames = reader.push(
      frame({ type: 'delta', text: '一' }) + frame({ type: 'delta', text: '二' }) + frame(ANSWER),
    );

    expect(frames.map((one) => one.type)).toEqual(['delta', 'delta', 'final']);
  });

  it('holds a frame back until its blank line arrives', () => {
    const reader = new AssistantFrameReader();

    // 没有收尾的空行就还不算一帧：这时候交出去等于把半截 JSON 当数据。
    expect(reader.push('data: {"type":"delta","text":"半')).toEqual([]);
    expect(reader.push('句"}\n')).toEqual([]);
    expect(reader.push('\n').map((one) => one.type)).toEqual(['delta']);
  });

  it('ignores a frame type it does not know instead of failing the turn', () => {
    // 前向兼容：服务端将来多一种进度帧，老前端必须继续能用。
    // 装饰没有否决权 —— 让一个不认识的帧把整轮对话打成失败是最糟的交换。
    const reader = new AssistantFrameReader();

    const frames = reader.push(frame({ type: 'progress', percent: 40 }) + frame(ANSWER));

    expect(frames.map((one) => one.type)).toEqual(['final']);
  });

  it('drops a status whose state it does not know, and keeps the ones it does', () => {
    const reader = new AssistantFrameReader();

    expect(reader.push(frame({ type: 'status', state: 'summarizing' }))).toEqual([]);

    const known = reader.push(frame({ type: 'status', state: 'reading', tool: 'conversation.search' }));
    expect(known).toEqual([{ type: 'status', state: 'reading', tool: 'conversation.search' }]);
  });

  it('ignores keep-alive comments and half-written frames', () => {
    const reader = new AssistantFrameReader();

    // `: keep-alive` 是 SSE 里唯一允许出现的「无载荷」行：代理按空闲超时掐连接时会用它。
    expect(reader.push(': keep-alive\n\n')).toEqual([]);
    // 断在一个帧中间的残余解不出来，但也不该把整轮拖垮。
    expect(reader.push('data: {"type":"delta","text":"半句\n\n')).toEqual([]);
    expect(warn).toHaveBeenCalled();
  });

  it('never mistakes a truncated final for a conclusion', () => {
    // 最危险的一种半帧：它长得像结论。少一个字符就不能认 ——
    // 认了就是把一段没写完的回答当成服务端的最终决定。
    const reader = new AssistantFrameReader();
    const complete = JSON.stringify(ANSWER);

    expect(reader.push(`data: ${complete.slice(0, complete.length - 1)}\n\n`)).toEqual([]);
    expect(reader.push(`data: ${complete}\n\n`).map((one) => one.type)).toEqual(['final']);
  });
});

describe('streamAssistantMessage：读一条真实的流', () => {
  const STREAM = frame({ type: 'status', state: 'thinking' })
    + frame({ type: 'delta', text: '张' })
    + frame({ type: 'delta', text: '总' })
    + frame({ type: 'reset' })
    + frame({ type: 'delta', text: '他' })
    + frame(ANSWER);

  it('reports progress as it comes and resolves with the conclusion', async () => {
    stubFetch(streamResponse(STREAM));
    const seen: string[] = [];

    const result = await streamAssistantMessage({ text: '张总的报价确认了吗' }, {
      onStatus: (status) => seen.push(`${status.type}:${status.state}`),
      onDelta: (text) => seen.push(`delta:${text}`),
      onReset: () => seen.push('reset'),
    });

    // 顺序就是屏幕上的顺序：先报在想 → 片段 → 重来一次 → 片段 → 结论。
    expect(seen).toEqual(['status:thinking', 'delta:张', 'delta:总', 'reset', 'delta:他']);
    expect(result).toMatchObject({ kind: 'ANSWER', message: '张总那边我看过了' });
  });

  it('carries the token and never asks for text/event-stream', async () => {
    window.localStorage.setItem('token', 'tok-1');
    const fetchMock = stubFetch(streamResponse(STREAM));

    await streamAssistantMessage({ conversationId: 'c-1', text: '你好' });

    const [url, init] = fetchMock.mock.calls[0] as [
      string,
      RequestInit & { headers: Record<string, string> },
    ];
    expect(url).toBe('/api/assistant/messages');
    expect(ASSISTANT_MESSAGES_URL).toBe('/api/assistant/messages');
    expect(init.method).toBe('POST');
    // 与 axios 实例的 withCredentials 对齐：换了传输层不该少带 cookie。
    expect(init.credentials).toBe('include');
    expect(init.headers.Authorization).toBe('Bearer tok-1');
    expect(JSON.parse(String(init.body))).toEqual({ conversationId: 'c-1', text: '你好' });
    // 关键的一条：设了 `Accept: text/event-stream`，未登录 / 开关没开那两种失败会退化成
    // 406 空响应（端点的 produces 只管成功路径）。浏览器默认的 `*/*` 两条路都通。
    expect(init.headers).not.toHaveProperty('Accept');
  });

  it('says the outcome is unknown when the stream ends without a conclusion', async () => {
    // 只收到了片段就没了。这与「服务端说失败了」是两件事：后者带着 errorCode 躺在 final 里，
    // 而这里服务端**什么都没说**。
    stubFetch(streamResponse(frame({ type: 'delta', text: '张总那边我' })));

    const failure = await failedTurn({ text: '在吗' });

    expect(failure.message).toContain('结果未知');
    expect(failure.reachedServer).toBe(false);
    // 没有 HTTP 状态码可以给它：这一轮的结果本来就不确定，编一个状态码是在装确定。
    expect(failure.status).toBeUndefined();
  });

  it('does not read a broken connection as a refusal from the server', async () => {
    stubFetch(streamResponse(frame({ type: 'delta', text: '张' }), new Error('socket hang up')));

    const failure = await failedTurn({ text: '在吗' });

    expect(failure.reachedServer).toBe(false);
    expect(failure.message).toContain('结果未知');
  });

  it('keeps the real status and code when the response never started', async () => {
    // 未登录 / 开关没开 / 参数不合法：服务端刻意在提交响应**之前**抛，就是为了这些情况
    // 还能有一个真正的 HTTP 状态码。前端必须把它原样带出来，而不是折成一句「助手失败」。
    stubFetch(jsonResponse(503, '{"code":"ASSISTANT_DISABLED","message":"助手功能未开启，请联系管理员配置"}'));

    const failure = await failedTurn({ text: '你好' });

    expect(failure.status).toBe(503);
    expect(failure.code).toBe('ASSISTANT_DISABLED');
    expect(failure.reachedServer).toBe(true);
    expect(failure.message).toBe('助手功能未开启，请联系管理员配置');
  });

  it('never hands a gateway error page to the user as the reason', async () => {
    stubFetch(jsonResponse(502, '<html><body>502 Bad Gateway</body></html>'));

    const failure = await failedTurn({ text: '你好' });

    expect(failure.status).toBe(502);
    expect(failure.message).toBe('助手请求失败（HTTP 502）');
    expect(failure.message).not.toContain('html');
  });

  it('clears the session and sends the user to the login page on 401', async () => {
    window.localStorage.setItem('token', 'expired');
    window.localStorage.setItem('username', '小森');
    // jsdom 不会真的导航，它只会往 stderr 吐一句 "Not implemented: navigation"。
    // 这里把它换成一个记事本：既消掉那条噪音，又能断言**把人送到哪儿去**。
    const location = { href: 'http://localhost/current' };
    vi.stubGlobal('location', location);
    stubFetch(jsonResponse(401, '{"code":"UNAUTHENTICATED","message":"请先登录"}'));

    await streamAssistantMessage({ text: '你好' }).catch(() => undefined);

    // 会话过期必须与普通请求表现一致：否则发消息这条路只会显示一句看不出原因的失败，
    // 而用户完全不知道自己该重新登录。清凭证与送回登录页两件事缺一不可 ——
    // 只清凭证，他会停在原地对着一屏读不出东西的界面。
    expect(window.localStorage.getItem('token')).toBeNull();
    expect(window.localStorage.getItem('username')).toBeNull();
    expect(location.href).toBe('/login');
  });

  it('blames the network only when the request never left', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    const failure = await failedTurn({ text: '你好' });

    expect(failure.reachedServer).toBe(false);
    expect(failure.message).toContain('网络');
  });

  it('explains a 200 that has no stream at all', async () => {
    // 代理把响应体剥掉或整个缓冲了。这不是用户能处理的事，所以文案不给建议、只说清现象，
    // 排障线索留在 console 与 deploy/README.md 的 nginx 段。
    stubFetch(streamResponse(null));

    const failure = await failedTurn({ text: '你好' });

    expect(failure.status).toBe(200);
    expect(failure.message).toContain('回答流');
    expect(warn).toHaveBeenCalled();
  });

  /** 断言它确实是以 {@link AssistantStreamFailure} 失败的，并把类型带回来给后面的具体断言。 */
  async function failedTurn(request: AssistantMessageRequest): Promise<AssistantStreamFailure> {
    const failure = await streamAssistantMessage(request).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(AssistantStreamFailure);
    return failure as AssistantStreamFailure;
  }
});

/** 一个只实现「被读」的响应体：按**七字节**吐块，读空后正常结束（或抛错模拟连接断掉）。 */
function bodyOf(payload: string, failure?: Error) {
  const bytes = new TextEncoder().encode(payload);
  let cursor = 0;
  return {
    getReader: () => ({
      read: async () => {
        if (cursor >= bytes.length) {
          if (failure) throw failure;
          return { done: true, value: undefined };
        }
        // 七字节：故意切在汉字（三字节）的中间，逼解码器自己缓冲。
        const value = bytes.slice(cursor, cursor + 7);
        cursor += 7;
        return { done: false, value };
      },
    }),
  };
}

/** 一条会吐 SSE 的 200（`payload` 传 `null` 表示干脆没有响应体）。 */
function streamResponse(payload: string | null, failure?: Error) {
  return {
    ok: true,
    status: 200,
    body: payload === null ? null : bodyOf(payload, failure),
  };
}

/** 一个「响应还没开始写」的失败：真正的状态码 + 原始响应体。 */
function jsonResponse(status: number, raw: string) {
  return {
    ok: false,
    status,
    body: null,
    text: async () => raw,
  };
}

function stubFetch(response: unknown) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}
