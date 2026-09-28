import { API_BASE, authToken, handleUnauthorized } from './session';
import type {
  AssistantMessageRequest,
  AssistantProgressFrame,
  AssistantStatusFrame,
  AssistantStreamFrame,
  AssistantStreamState,
  AssistantTurnResult,
} from './types';

/**
 * `POST /api/assistant/messages` 的流式客户端。
 *
 * <h2>为什么是 `fetch` 而不是 axios</h2>
 * 这条端点的响应体是一串**边跑边写**的 SSE 帧（服务端 `AssistantEventStream`）。
 * axios 会把整个响应体读完再交给调用方，逐字显示无从谈起 —— 必须自己拿
 * `response.body` 这个流来读。
 *
 * <h2>刻意不做的事：不设 `Accept: text/event-stream`</h2>
 * 端点的 `produces` 只管**成功**路径。失败时（未登录 401、开关没开 503、参数不合法 400）
 * 服务端要写的是 JSON 错误体，而内容协商看的是请求的 `Accept` —— 写了
 * `text/event-stream` 会让这些错误退化成 **406 空响应**，「助手没开启」于是变成
 * 一句没头没尾的失败。浏览器默认允许任意媒体类型，两条路都通，什么都不设才是对的。
 */

/**
 * 这一轮的请求地址。
 *
 * `/api` 前缀在这里是**拼出来**的、不是 axios 的 `baseURL`：`fetch` 没有那个概念。
 * 别把它写死成字面量，否则改前缀时这条流会静默地 404（而其它端点都正常）。
 */
export const ASSISTANT_MESSAGES_URL = `${API_BASE}/assistant/messages`;

/**
 * 流式端点自己的失败类型。
 *
 * 它必须是个**具体类型**而不是一个形状相像的对象：`useAssistant` 的失败分类靠
 * `instanceof` 认出它，才能把「服务端答了 503」与「流走到一半断了」分开 ——
 * 前者不该建议用户重试网络，后者必须建议。
 */
export class AssistantStreamFailure extends Error {
  /** 非 2xx 时的 HTTP 状态码；流中断时没有。 */
  readonly status: number | undefined;
  /** 非 2xx 时服务端给的错误码（如 `ASSISTANT_DISABLED`）。 */
  readonly code: string | undefined;
  /**
   * 服务端有没有对这一轮给出答复。
   *
   * `false` 表示「请求根本没发出去」或「流断了、结果未知」—— 两者都值得再发一次。
   * 它与 `status` 是两件事：200 也可能 `reachedServer=false`（流断了）。
   */
  readonly reachedServer: boolean;

  constructor(message: string, options: { status?: number; code?: string; reachedServer: boolean }) {
    super(message);
    this.name = 'AssistantStreamFailure';
    this.status = options.status;
    this.code = options.code;
    this.reachedServer = options.reachedServer;
  }
}

export type AssistantStreamHandlers = {
  /** 服务端在报进度。它只是「还在动」的证据，不是结论。 */
  onStatus?: (frame: AssistantStatusFrame) => void;
  /** 一段**增量**文本（见 `AssistantDeltaFrame`）。 */
  onDelta?: (text: string) => void;
  /** 把已经显示出来的片段丢掉。 */
  onReset?: () => void;
};

const DATA_PREFIX = 'data:';

/**
 * SSE 的**切分器**：把字节流切成帧。
 *
 * 它解决的唯一问题是「一块数据不保证是一帧」。网络可以在任意位置切开，而按字符推出来的
 * 中文一个字就是三个字节 —— 块边界落在一个汉字的中间是**常态**，不是边界情况。
 * 所以这里既要有字符串缓冲（一帧被切成两半），上层的解码也要 `{ stream: true }`
 * （一个字符被切成两半）。
 */
export class AssistantFrameReader {
  private pending = '';

  /**
   * 喂一块文本，吐出这一块里能凑齐的帧（可能是 0 个或多个）。
   *
   * 只认 `\n\n` 分隔：这一条流的另一端是我们自己的服务端，它每一帧都写成
   * `data: {...}\n\n`，不存在 CRLF、也不存在多行 `data:`。
   */
  push(chunk: string): AssistantStreamFrame[] {
    this.pending += chunk;
    const frames: AssistantStreamFrame[] = [];
    for (;;) {
      const boundary = this.pending.indexOf('\n\n');
      if (boundary < 0) break;
      const block = this.pending.slice(0, boundary);
      this.pending = this.pending.slice(boundary + 2);
      const frame = parseBlock(block);
      if (frame) frames.push(frame);
    }
    return frames;
  }
}

/**
 * 一块（两个 `\n\n` 之间）→ 一帧，认不出来就返回 `null`：**不抛**。
 *
 * 这条前向兼容的要求很具体：服务端将来多一种 `type`（再加一种进度），
 * 老前端必须继续能用。进度是装饰，**装饰不该有否决权** ——
 * 让一个不认识的帧把整轮对话打成失败，是比丢一个进度提示糟糕得多的交换。
 */
function parseBlock(block: string): AssistantStreamFrame | null {
  const payloads: string[] = [];
  for (const line of block.split('\n')) {
    // `: keep-alive` 这类注释行、`event:` / `id:` / `retry:` 都不参与载荷。
    if (!line.startsWith(DATA_PREFIX)) continue;
    // 规范允许冒号后跟一个无意义空格，服务端就是那么写的。
    payloads.push(line.slice(DATA_PREFIX.length).replace(/^ /, ''));
  }
  if (payloads.length === 0) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(payloads.join('\n'));
  } catch {
    // 半帧（连接断在一个帧的中间）或者对面根本不是我们以为的那个服务端。
    console.warn('assistant: 流里出现无法解析的帧', block);
    return null;
  }
  return asFrame(parsed);
}

function asFrame(parsed: unknown): AssistantStreamFrame | null {
  if (typeof parsed !== 'object' || parsed === null) return null;
  const frame = parsed as Record<string, unknown>;

  if (frame.type === 'delta' && typeof frame.text === 'string') {
    return { type: 'delta', text: frame.text };
  }
  if (frame.type === 'reset') {
    return { type: 'reset' };
  }
  if (frame.type === 'status' && isStreamState(frame.state)) {
    return {
      type: 'status',
      state: frame.state,
      tool: typeof frame.tool === 'string' ? frame.tool : undefined,
    };
  }
  if (frame.type === 'final' && typeof frame.kind === 'string') {
    // 把 `type` 摘掉交给上层：结论那一份就是 `AssistantTurnResult`，别让它带着协议字段到处走。
    const { type: _protocol, ...result } = frame;
    return { type: 'final', result: result as unknown as AssistantTurnResult };
  }
  return null;
}

function isStreamState(value: unknown): value is AssistantStreamState {
  return value === 'thinking' || value === 'reading';
}

/**
 * 发一轮对话，边收边报；返回的是**这一轮的结论**。
 *
 * 失败有两种，都已经折进 {@link AssistantStreamFailure}：
 * 1. 服务端还没开始写响应就失败了（未登录 / 开关没开 / 参数不合法）→ 真正的 HTTP 状态码；
 * 2. 流开着的时候失败（模型挂了、连接断了）→ 前者在 `final` 帧里，后者**根本没有 `final`**。
 *    这条区别是「服务端说失败了」与「不知道这一轮做了什么」的分界，别把它压成一句话。
 */
export async function streamAssistantMessage(
  request: AssistantMessageRequest,
  handlers: AssistantStreamHandlers = {},
): Promise<AssistantTurnResult> {
  const response = await openStream(request);

  const body = response.body;
  if (!body) {
    // 200 但没有可读的流：中间有东西（代理、企业网关）把响应体剥掉或者整个缓冲了。
    // 这不是用户能处理的事，所以文案不给建议、只说清发生了什么，排障线索留给 console
    // 与 `deploy/README.md` 的 nginx 段。
    console.warn('assistant: 200 但没有可读的响应流，检查代理是否缓冲/剥掉了 SSE 响应体');
    throw new AssistantStreamFailure('这一轮没能开始：浏览器没有收到助手的回答流', {
      status: response.status,
      reachedServer: true,
    });
  }

  const reader = body.getReader();
  const decoder = new TextDecoder();
  const frames = new AssistantFrameReader();
  let result: AssistantTurnResult | null = null;

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      // `{ stream: true }` 不是可选项：一个汉字三个字节，块边界落在汉字中间是常态。
      for (const frame of frames.push(decoder.decode(value, { stream: true }))) {
        if (frame.type === 'final') {
          // 服务端保证有且只有一帧 `final`、且它在最后。收到它 = 这一轮有结论了。
          if (result === null) result = frame.result;
          continue;
        }
        dispatchProgress(frame, handlers);
      }
    }
  } catch (broken) {
    // 连接断在流中间会走到这里。某个回调自己抛了也会 —— 两种都是「结论没拿到」，
    // 所以下面的文案只说「结果未知」，不谎报成「网络断了」。
    console.warn('assistant: 读取回答流时中断', broken);
  }

  if (result) return result;

  // 没有 `final`。这与「服务端说失败了」是两件事：后者带着 `errorCode` 躺在 `final` 里。
  // 用户唯一能做的是再发一次，所以这句话要说出这一点。
  throw new AssistantStreamFailure('助手的回答中途断了，这一轮的结果未知，再发一次通常就好了', {
    reachedServer: false,
  });
}

async function openStream(request: AssistantMessageRequest): Promise<Response> {
  const token = authToken();
  let response: Response;
  try {
    response = await fetch(ASSISTANT_MESSAGES_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      // 与 axios 实例的 `withCredentials` 对齐：同一个登录态，不该因为换了传输层就少带 cookie。
      credentials: 'include',
      body: JSON.stringify(request),
    });
  } catch (offline) {
    // `fetch` 只在请求**根本没发出去**时 reject（断网、DNS 失败、被 CORS 挡）。
    // 服务端答了错反而不走这里 —— 那是下面的非 2xx 分支。
    console.warn('assistant: 发送失败', offline);
    throw new AssistantStreamFailure('无法连接到服务器，请检查网络后重试', { reachedServer: false });
  }
  if (response.ok) return response;
  throw await failureOf(response);
}

/**
 * 非 2xx → 与服务端其它端点同族的失败：状态码 + `{code, message}`。
 *
 * 会走到这里的只有**响应还没开始写**的那些失败（未登录 / 开关没开 / 参数不合法）——
 * 服务端刻意让它们在提交响应之前抛（见 `AssistantController.messages`），
 * 就是为了这些情况还能有一个真正的 HTTP 状态码。流一旦开写，任何失败都只能藏在
 * `final` 帧里，那时 HTTP 一定是 200。
 */
async function failureOf(response: Response): Promise<AssistantStreamFailure> {
  const raw = await response.text().catch(() => '');
  let code: string | undefined;
  let message: string | undefined;
  try {
    const parsed = JSON.parse(raw) as { code?: unknown; message?: unknown };
    if (typeof parsed.code === 'string') code = parsed.code;
    if (typeof parsed.message === 'string' && parsed.message.trim()) message = parsed.message.trim();
  } catch {
    // 不是 JSON：多半是网关自己的错误页（nginx 的 502 之类）。
    // 留空走下面的兜底文案，别把一整页 HTML 当成给人看的话。
  }
  if (response.status === 401) {
    handleUnauthorized();
  }
  return new AssistantStreamFailure(message ?? `助手请求失败（HTTP ${response.status}）`, {
    status: response.status,
    code,
    reachedServer: true,
  });
}

function dispatchProgress(frame: AssistantProgressFrame, handlers: AssistantStreamHandlers): void {
  switch (frame.type) {
    case 'status':
      handlers.onStatus?.(frame);
      break;
    case 'delta':
      handlers.onDelta?.(frame.text);
      break;
    case 'reset':
      handlers.onReset?.();
      break;
  }
}
