import { useCallback, useEffect, useRef, useState } from 'react';
import {
  cancelAssistantAction,
  confirmAssistantAction,
  fetchAssistantConversation,
  fetchLatestAssistantConversation,
  sendAssistantMessage,
} from '../../api/endpoints';
import { AssistantStreamFailure } from '../../api/assistantStream';
import type { AssistantStreamHandlers } from '../../api/assistantStream';
import type {
  AssistantConversationMessage,
  AssistantHistoryTurn,
  AssistantProposal,
  AssistantStreamState,
  AssistantTurnKind,
  AssistantTurnResult,
} from '../../api/types';
import { publishAssistantEvent } from '../../assistant/assistantEvents';

/**
 * 一条聊天记录。用户与助手的共用同一形状，靠 `role` + `kind` 区分呈现方式。
 *
 * `kind` 直接对应服务端的终点，而不是前端自己推断的 —— 「这一轮到底做成了什么」
 * 只有服务端知道，前端派生出来的状态早晚会和真相对不上。
 */
export type AssistantChatItem = {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  /** 仅助手消息：这一轮属于哪种终点。 */
  kind?: AssistantTurnKind;
  /** 仅 `kind=CONFIRMATION_REQUIRED`：待确认动作本身，卡片据此渲染。 */
  proposal?: AssistantProposal;
  /** 仅失败的消息：结果码或 HTTP 错误码，用于区分原因。 */
  errorCode?: string;
  /** 这条失败是否值得让用户按「重试」。参数非法 / 授权已失效都不值得。 */
  retryable?: boolean;
  /**
   * 这一轮**还在推**。
   *
   * 它为 `true` 时 `kind` 还没定 —— 也就是说此刻**不许**把这条渲染成任何一种结论
   * （成功 / 失败 / 待确认都还不知道）。面板据此显示进度与光标，而不是一个空气泡。
   */
  streaming?: boolean;
  /** 流里报的进度（「正在查资料…」）。只在还没有正文时显示。 */
  progress?: string;
};

/** 功能不可用。`DISABLED`（没开开关）与 `UNAVAILABLE`（模型服务挂了）的处置完全不同。 */
export type AssistantUnavailable = { code: string; message: string };

/** 服务端错误码。面板据此决定「输入框还能不能打字」，因此不做字面量比较。 */
export const AssistantCodes = {
  DISABLED: 'ASSISTANT_DISABLED',
  PROVIDER_UNAVAILABLE: 'ASSISTANT_UNAVAILABLE',
} as const;

/**
 * 前端最多回带几条历史。
 *
 * 它现在**只是兜底**：会话号有效且服务端有该会话的记录时，历史以库为准
 * （见 `AssistantController.historyFor`），这段前端历史只在服务端查不到时才被采用。
 * 保留它是因为「写入曾静默失败」会留下库里有缺口的会话，那时它是唯一的来源 ——
 * 但要清楚：**改这个常量不再能改变模型看到什么**。
 * 与服务端 `assistant.max-history-turns` 的默认值同口径。
 */
const MAX_HISTORY_TURNS = 8;

/** 会话号在前端的存放位置。刷新页面后靠它接上同一段对话。 */
const CONVERSATION_STORAGE_KEY = 'assistant.conversationId';

/**
 * 「用户在这个设备上主动开了一段新会话，而它还没说过话」。
 *
 * <h2>为什么需要单独记一件事</h2>
 * 服务端能回答「你最近在哪个会话里」，但它是**从消息表推**出来的 ——
 * 一段刚开、还没说过话的新会话在它眼里根本不存在，问它只会拿回上一段对话。
 * 而「点过新会话、又重开面板」的用户要的恰恰是空白：那一刻服务端兜底会把用户
 * 主动丢掉的那段对话自己拉回来，正好否定了他唯一那个「清空上下文」的入口。
 *
 * <p>所以这个标记只有一个作用：在它还在的时候，<b>不要去问服务端</b>。
 * 服务端手里没有能区分「用户主动开了新会话」与「这是台新设备」的信息 ——
 * 这个区别只存在于用户点那一下的意图里，只能由前端记着。
 *
 * <h2>什么时候撤掉</h2>
 * 服务端确认收到这个会话的第一轮之后（见 `applyResult`）。那一刻库里已经有了新会话的行，
 * 服务端的答案自动变成正确的那个，标记的使命就结束了。
 */
const NEW_CONVERSATION_STORAGE_KEY = 'assistant.conversationStarted';

const TURN_KINDS: readonly AssistantTurnKind[] = [
  'QUESTION',
  'CONFIRMATION_REQUIRED',
  'EXECUTED',
  'ANSWER',
  'ERROR',
];

/**
 * 进度说人话。
 *
 * 刻意**不显示工具名**：那是内部标识（`conversation.search` 之类），而面板是给人看的。
 * 用户此刻要判断的只有一件事 —— 它还在动，那就等。服务端仍然把工具名放在帧里，
 * 那是给排障用的（`curl` 打这条流就能看见它在查什么）。
 */
const PROGRESS_TEXT: Record<AssistantStreamState, string> = {
  thinking: '正在想…',
  reading: '正在查资料…',
};

let sequence = 0;
const nextId = () => `assistant-item-${++sequence}`;

/**
 * 会话号由前端生成。
 *
 * 服务端只把它当**分组标签**（把同一段对话的消息与审计串起来），不是凭证 ——
 * 归属一律按登录用户判定，所以拿别人的会话号也读不到任何东西。
 * 正因如此前端可以自由生成，不必先向服务端申领一个 id。
 */
function newConversationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  // 非安全上下文（如 http 的局域网地址）没有 randomUUID。退化成 v4 形状的随机串：
  // 它只是分组标签、不承担安全职责，所以这里不需要密码学强度的随机源。
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
    const random = (Math.random() * 16) | 0;
    const value = char === 'x' ? random : (random & 0x3) | 0x8;
    return value.toString(16);
  });
}

function readStoredConversationId(): string {
  try {
    const saved = window.localStorage.getItem(CONVERSATION_STORAGE_KEY);
    if (saved) return saved;
  } catch {
    // localStorage 不可用（隐私模式、被策略禁用）：退化成「本次页面会话内有效」，
    // 对话照常能用，只是刷新后换一个会话号。
  }
  return newConversationId();
}

function storeConversationId(id: string): void {
  try {
    window.localStorage.setItem(CONVERSATION_STORAGE_KEY, id);
  } catch {
    // 同上：存不下不是错误，只是刷新后接不上。
  }
}

function readStoredNewConversation(): boolean {
  try {
    return window.localStorage.getItem(NEW_CONVERSATION_STORAGE_KEY) === '1';
  } catch {
    // 读不到就当它不在。后果只是「多问一次服务端」—— 那比误判成「用户刚开过新会话」
    // 而永久不兜底要轻得多：后者会一直静默失效，且没有人会发现。
    return false;
  }
}

function storeNewConversation(value: boolean): void {
  try {
    if (value) {
      window.localStorage.setItem(NEW_CONVERSATION_STORAGE_KEY, '1');
    } else {
      window.localStorage.removeItem(NEW_CONVERSATION_STORAGE_KEY);
    }
  } catch {
    // 存不下时行为退化成「每次打开都问一次服务端」：对一段没有内容的新会话，
    // 服务端会答上一个会话，于是用户重开面板时旧对话会回来。
    // 这是隐私模式下的已知退化，不额外处理 —— 那里 localStorage 本来就不作数。
  }
}

function isTurnKind(value: unknown): value is AssistantTurnKind {
  return typeof value === 'string' && (TURN_KINDS as readonly string[]).includes(value);
}

/**
 * 这一轮结果值不值得让用户按「重试」。
 *
 * 判据是**「再来一次会不会不一样」**：模型供应商不可达是环境问题，重发很可能就通了；
 * 而「参数不合法」「引用的待办已经不在了」这类结论服务端已经想清楚了，再点一次还是同一句话。
 *
 * <p>这条判据以前长在 HTTP 状态码上（模型挂了会回 503）。改成流式之后，同一件事藏在
 * `final` 帧里、HTTP 状态是 200 —— 于是它必须回到**结果本身**来判。这其实更结实：
 * 「哪一类失败值得重试」本来就与它是怎么传到前端来的无关。
 */
function isRetryableResult(result: AssistantTurnResult): boolean {
  return result.kind === 'ERROR' && result.errorCode === AssistantCodes.PROVIDER_UNAVAILABLE;
}

/** 把一轮结果变成一条聊天记录。 */
function itemOf(result: AssistantTurnResult): AssistantChatItem {
  return {
    id: nextId(),
    role: 'assistant',
    text: typeof result.message === 'string' ? result.message : '',
    kind: result.kind,
    proposal: result.proposal,
    errorCode: result.errorCode,
    retryable: isRetryableResult(result),
  };
}

/**
 * 把服务端回放的历史行变成聊天记录。
 *
 * 历史里**不恢复确认卡片**（回放接口不返回 `proposal`），因此一条
 * `CONFIRMATION_REQUIRED` 的旧记录会退化成带标签的普通消息。这是刻意的：
 * 那次授权大概率已经确认、取消或过期，把按钮画出来只会引诱用户点一个注定失败的键。
 * 真要恢复卡片，应当查待确认动作的**当前状态**，而不是从历史里推断。
 */
function itemOfHistory(message: AssistantConversationMessage): AssistantChatItem {
  return {
    id: nextId(),
    role: message.role,
    text: message.text,
    kind: isTurnKind(message.kind) ? message.kind : undefined,
    // 历史消息一律不给「重试」：重试重发的是**上一次实际发出的请求**，
    // 而这条历史可能来自很久以前，甚至另一个页面会话，点了会很意外。
    retryable: false,
  };
}

type HttpFailure = {
  status?: number;
  code?: string;
  message: string;
  reachedServer: boolean;
};

/**
 * 把一次 axios 失败拆成「要不要重试」与「换什么文案」。
 *
 * 判据是**失败发生在哪一层**：请求根本没到达服务端（网络断、超时、网关 5xx）值得重试；
 * 服务端已经明确回答「你的请求不合法 / 这条授权已失效」则不值得 —— 重试只是再撞一次同一堵墙。
 * 把这两种混成一句「操作失败」，用户就永远不知道该不该再点一次。
 */
function classify(error: unknown): HttpFailure {
  // 流式端点自己抛的失败。它已经把「HTTP 层失败」与「流断了」折成同一组字段，
  // 这里直接信它，而不是去猜它的形状。
  if (error instanceof AssistantStreamFailure) {
    return {
      status: error.status,
      code: error.code,
      message: error.message,
      reachedServer: error.reachedServer,
    };
  }
  const response = (error as {
    response?: { status?: number; data?: { code?: unknown; message?: unknown } };
  } | null)?.response;
  if (!response) {
    return { message: '无法连接到服务器，请检查网络后重试', reachedServer: false };
  }
  const status = typeof response.status === 'number' ? response.status : undefined;
  const data = response.data;
  const code = typeof data?.code === 'string' ? data.code : undefined;
  const serverMessage = typeof data?.message === 'string' && data.message.trim()
    ? data.message.trim()
    : undefined;
  return {
    status,
    code,
    message: serverMessage ?? `助手请求失败（HTTP ${status ?? '未知'}）`,
    reachedServer: true,
  };
}

function isRetryable(failure: HttpFailure): boolean {
  if (!failure.reachedServer) return true;
  if (failure.status === 503) return failure.code === AssistantCodes.PROVIDER_UNAVAILABLE;
  return typeof failure.status === 'number' && failure.status >= 500;
}

/**
 * 助手面板的会话状态机（设计文档 §11.2）。
 *
 * <h2>这里最贵的一个错误</h2>
 * 把「已受理」「执行中」渲染成「已完成」。用户看到「已删除」就不会再去核对，而待办还在那里。
 * 因此：`kind=EXECUTED` 只在**服务端说执行完成**之后才产生，且请求在飞的过程中
 * (`busy=true`) 确认卡片只会显示「执行中」，绝不会变成结果文案。
 *
 * <h2>另一条底线：失败不许被静默成成功</h2>
 * 所有失败都会变成一条 `kind=ERROR` 的消息留在对话里（而不是一个转瞬即逝的 toast）——
 * 用户回翻时能看见「那次没成」，而不是只记得自己说过。
 *
 * <h2>「我记不住前面了」也必须有出口</h2>
 * 服务端会裁剪过长的历史，而模型**察觉不到**自己少了语境 —— 它会拿着断掉的开头照样自信作答。
 * 所以服务端把「丢了多少条」放进响应（`historyTrim`），这里把它提升成会话级状态交给面板显示。
 * 这是同一条底线的第二个面：不许把「能力已经变弱」静默掉。
 *
 * <p>已知取舍：`trimmedHistory` 只活在内存里。刷新后回放接口不返回这个信息，提示会消失 ——
 * 要让它持久，得把裁剪规模落到消息行上（那是一次迁移，目前不做）。
 *
 * <h2>同一条底线的第三面：接不上上次，也要说出来</h2>
 * 打开面板时若回放失败，以前只留下一行 `console.warn`，于是「助手没开启」与
 * 「刚开了一段新会话」在界面上**完全一样**（都是一片空白）。现在它分两路说出来：
 * 功能不可用（503）复用 `unavailable`（面板顶部显示「AI 助手未开启」并禁用输入框），
 * 其余失败进 `historyError`（一条独立的提示，不写进对话 —— 它不属于任何一轮对话）。
 *
 * <h2>逐字回答：变的是一条记录，不是消息在变</h2>
 * `/messages` 是流式端点（见 `AssistantStreamHandlers`）。处理它的关键只有一条：
 * **先占位、后覆盖**。占位记录承担「请求已经出去了」这个事实；片段到达就往它身上长
 * （`delta` 是**增量**）；收到 `reset` 就清空重来；收到 `final` 就整条换成结论。
 * 于是屏幕上永远只有一份东西，而且最后显示的那一份一定是服务端给的结论 ——
 * 片段只是草稿，它**不参与**「这一轮到底做成了什么」的判断。
 *
 * <p>由此带出一个必须记住的后果：**失败不再以 HTTP 状态码的形式到达**。模型挂了、
 * 参数不合法、引用的待办没了，现在都是 200 + `final{kind=ERROR}`，于是「能不能重试」
 * 必须回到结果本身来判（见 `isRetryableResult`）。只有「响应还没开始写」的失败
 * （未登录 / 开关没开）还保留着真正的 HTTP 状态码。
 *
 * <h2>会话号的三个来源，优先级是刻意的</h2>
 * <ol>
 *   <li>浏览器本地存的那一个（常态）；</li>
 *   <li>本地那个读不出东西时，问服务端「我最近在哪个会话里」（清缓存/换设备/换账号后接得上）；</li>
 *   <li>都没有时新开一段。</li>
 * </ol>
 * 第 2 步<b>不许覆盖用户主动开的新会话</b>（见 `NEW_CONVERSATION_STORAGE_KEY`）。
 * 换会话号（「新会话」）仍然是清空上下文唯一的入口。
 */
export function useAssistant() {
  const [items, setItems] = useState<AssistantChatItem[]>([]);
  const [busy, setBusyState] = useState(false);
  const [pending, setPending] = useState<AssistantProposal | null>(null);
  const [unavailable, setUnavailable] = useState<AssistantUnavailable | null>(null);
  const [conversationId, setConversationId] = useState<string>(() => readStoredConversationId());
  /**
   * 最近一次「语境被裁剪」的规模，`null` 表示这个会话里还没发生过。
   *
   * 之所以是**会话级状态**而不是每条消息上的字段：同一个会话里一旦开始丢，后面每一轮都会丢，
   * 逐条显示会变成一片重复的噪音。用户需要知道的是「助手从某一刻起看不见前面了」这件事本身。
   */
  const [trimmedHistory, setTrimmedHistory] = useState<number | null>(null);
  const [compactedHistory, setCompactedHistory] = useState<number | null>(null);
  /**
   * 上次的对话没能读回来。与 `unavailable` 分开：那个说的是「助手用不了」，
   * 这个说的是「助手能用，但上面这段对话不完整」—— 两件事的下一步动作完全不同。
   *
   * 它不会因为后来某一轮成功就清掉：那条提示说的是**屏幕上缺了一段**，
   * 一次成功的新请求并不能把缺掉的那段补回来（与 `trimmedHistory` 同一个道理）。
   * 只有「新会话」会清它 —— 那时面板本来就不该有任何历史。
   */
  const [historyError, setHistoryError] = useState<string | null>(null);

  useEffect(() => {
    storeConversationId(conversationId);
  }, [conversationId]);

  /**
   * `busy` 的同步镜像。`send` / `retry` 会被用户连点两下，而 `setBusyState` 是异步的，
   * 只靠 `busy` 拦不住同一帧里的第二次调用 —— 那会发出两条一模一样的请求。
   */
  const busyRef = useRef(false);
  const setBusy = useCallback((value: boolean) => {
    busyRef.current = value;
    setBusyState(value);
  }, []);

  /**
   * 上一次实际发出的请求。保留它是为了「重试」能重发**同一句话**，
   * 而不是让用户重新打一遍 —— 重试的价值就在于他不用再想一次。
   */
  const lastAttempt = useRef<{ history: AssistantHistoryTurn[]; text: string } | null>(null);

  /**
   * 历史是否已经回放过。用 ref 而不是 state：StrictMode 下 effect 会跑两次，
   * state 的更新在同一帧里还没生效，会发出两次回放请求。
   */
  const hydratedRef = useRef(false);

  /**
   * 当前这段会话里是否已经有**用户产生的内容**（说过话，或刚点了「新会话」）。
   *
   * 服务端兜底要换会话号之前会看它。用 ref 而不是读 `items`：`loadHistory` 是个
   * 只依赖会话号的回调，闭包里的 `items` 停在挂载那一刻 —— 用它判断会永远看到空数组。
   */
  const localActivityRef = useRef(false);

  const append = useCallback((item: AssistantChatItem) => {
    setItems((prev) => [...prev, item]);
  }, []);

  /**
   * 就地改写一条记录。流里的进度与正文片段都是**同一条记录的变化**，不是新消息 ——
   * 每收一个字就追加一条消息的话，屏幕上会变成一片一个字的瀑布。
   */
  const updateItem = useCallback((id: string, change: (item: AssistantChatItem) => AssistantChatItem) => {
    setItems((prev) => prev.map((item) => (item.id === id ? change(item) : item)));
  }, []);

  /**
   * 把占位记录换成最终记录（结论或失败）。没有占位就直接追加。
   *
   * 占位被**覆盖**而不是留在原地：它是草稿，不是一条消息。留着它，对话里就会出现
   * 「助手说了两遍」—— 一遍是半截的、一遍是完整的。
   */
  const settle = useCallback((placeholderId: string | undefined, item: AssistantChatItem) => {
    if (!placeholderId) {
      append(item);
      return;
    }
    setItems((prev) => {
      const index = prev.findIndex((existing) => existing.id === placeholderId);
      // 占位已经不在了（比如用户在这中间点了「新会话」）：只能追加。
      // 相比之下，让这一轮的结论静默消失要糟得多。
      if (index < 0) return [...prev, item];
      const next = [...prev];
      // 沿用占位的 id：React 的 key 不变，这一行就不会先卸载再挂回来（屏幕上闪一下）。
      next[index] = { ...item, id: placeholderId };
      return next;
    });
  }, [append]);

  /**
   * 处理一次「这一轮没成」：HTTP 层失败、或流断在半路。
   *
   * `placeholderId` 是流式那一轮先放下的占位记录 —— 失败也要**覆盖它**。
   * 流断在半路时这一点尤其要紧：屏幕上那半句话必须消失。半句话会被当成整句话读，
   * 而「`final` 才是权威」这条不变式不允许屏幕上留一句无法闭合的草稿。
   */
  const applyFailure = useCallback((error: unknown, placeholderId?: string) => {
    const failure = classify(error);
    if (failure.status === 503
      && (failure.code === AssistantCodes.DISABLED || failure.code === AssistantCodes.PROVIDER_UNAVAILABLE)) {
      setUnavailable({ code: failure.code, message: failure.message });
    }
    settle(placeholderId, {
      id: nextId(),
      role: 'assistant',
      text: failure.message,
      kind: 'ERROR',
      errorCode: failure.code,
      retryable: isRetryable(failure),
    });
  }, [settle]);

  /**
   * 处理一次「回放历史」失败。与 {@link applyFailure} 的区别不是文案，而是**它不属于任何一轮对话**：
   * 往对话里塞一条失败消息，用户会以为「我刚才那句话失败了」，而他还没说话。
   *
   * <p>503 走 `unavailable` 这一路（而不是 `historyError`）：助手没开启时，
   * 面板要显示的是与发送失败时**同一句**「AI 助手未开启」，并顺手禁用输入框。
   * 这一种失败最值得说出来 —— 它长得最像「这里本来就什么都没有」。
   */
  const applyReplayFailure = useCallback((error: unknown) => {
    const failure = classify(error);
    if (failure.status === 503
      && (failure.code === AssistantCodes.DISABLED || failure.code === AssistantCodes.PROVIDER_UNAVAILABLE)) {
      setUnavailable({ code: failure.code, message: failure.message });
      return;
    }
    setHistoryError(failure.message);
  }, []);

  /**
   * 处理一轮的结论。确认 / 取消接口也走它 —— 那两条路没有流、没有占位，
   * 因此 `placeholderId` 省略，行为与以前完全一样（直接追加一条）。
   */
  const applyResult = useCallback((result: AssistantTurnResult, placeholderId?: string) => {
    // 服务端就这一轮给了结论 ⇒ 功能是开着的。**但「模型挂了」也走得通这条路**：
    // 它同样是一轮正常的 200，只不过 `kind=ERROR`、`errorCode=ASSISTANT_UNAVAILABLE`。
    // 所以这里不能一律清掉 `unavailable` —— 那会让面板顶部的「模型服务暂时不可用」
    // 被这一行自己抹掉，而那正是用户唯一能看到的「不是你的话有问题，是对面暂时不行」。
    if (result.kind === 'ERROR' && result.errorCode === AssistantCodes.PROVIDER_UNAVAILABLE) {
      setUnavailable({ code: AssistantCodes.PROVIDER_UNAVAILABLE, message: result.message });
    } else {
      setUnavailable(null);
    }
    // 服务端确认收到这一轮 ⇒ 库里已经有了这个会话的行，「服务端兜底」从此会给出正确的答案，
    // 那个「别问服务端」的标记可以撤掉了。
    //
    // 撤掉的依据是「服务端确认」而不是「用户按下发送」：失败的发送不会在库里留下任何东西，
    // 那时撤掉标记，重开面板就会把用户刚主动丢掉的那段对话又拉回来。
    storeNewConversation(false);
    // 只在字段**出现**时更新，绝不因为「没看到」而清空：确认 / 取消的响应本来就不带
    // `historyTrim`（它们不走 /messages），按「没看到 = 没裁剪」处理会把提示误清掉。
    if (result.historyTrim) {
      setTrimmedHistory(result.historyTrim.droppedMessages);
    }
    if (result.historyCompaction) {
      setCompactedHistory(result.historyCompaction.summarizedMessages);
    }
    settle(placeholderId, itemOf(result));
    if (result.kind === 'CONFIRMATION_REQUIRED' && result.proposal) {
      // 服务端已把这条动作落库；卡片上的确认只认这个 id。
      setPending(result.proposal);
      return;
    }
    setPending(null);
    if (result.kind === 'EXECUTED') {
      // 只有真的执行成功才广播：待确认、取消、失败都不该让别的页面去重读数据。
      publishAssistantEvent({ type: 'assistant-action-executed' });
    }
  }, [settle]);

  const dispatch = useCallback(async (
    history: AssistantHistoryTurn[],
    text: string,
    appendUser: boolean,
  ) => {
    lastAttempt.current = { history, text };
    if (appendUser) {
      // 一旦用户在这里说过话，这段会话号就不能再被服务端兜底换掉 ——
      // 换掉之后他刚说的话虽然还画在屏幕上，归属却已经不是当前上下文了。
      localActivityRef.current = true;
      append({ id: nextId(), role: 'user', text });
    }
    setBusy(true);

    /*
     * 先放一条空记录，让后面的片段往它身上长。
     *
     * 没有它的话，从按下发送到第一段文字到达之间有**几十秒**的静默（只读那一轮要查资料），
     * 而那段时间屏幕上什么都不会变 —— 用户只会以为没发出去，然后再点一次。
     * 所以这条占位记录不是视觉装饰，它承担的是「请求已经出去了」这个事实。
     */
    const placeholderId = nextId();
    append({
      id: placeholderId,
      role: 'assistant',
      text: '',
      streaming: true,
      progress: PROGRESS_TEXT.thinking,
    });

    const handlers: AssistantStreamHandlers = {
      onStatus: (frame) => updateItem(placeholderId, (item) => ({
        ...item,
        progress: PROGRESS_TEXT[frame.state],
      })),
      // 增量：往已经显示的正文后面接，不是覆盖。
      onDelta: (delta) => updateItem(placeholderId, (item) => ({ ...item, text: item.text + delta })),
      // 模型这一轮重来过：屏幕上那段草稿已经不作数了，清掉再说。
      onReset: () => updateItem(placeholderId, (item) => ({
        ...item,
        text: '',
        progress: '正在重新回答…',
      })),
    };

    try {
      applyResult(
        await sendAssistantMessage({ conversationId, history, text }, handlers),
        placeholderId,
      );
    } catch (error) {
      applyFailure(error, placeholderId);
    } finally {
      setBusy(false);
    }
  }, [append, applyFailure, applyResult, conversationId, setBusy, updateItem]);

  const send = useCallback((text: string) => {
    const trimmed = text.trim();
    if (!trimmed || busyRef.current) return;
    // 历史只取角色与文本：不夹带任何前端状态，更不能借它给自己换个身份。
    //
    // 注意这段历史的**作用已经变了**：会话号有效且有记录时服务端以库为准，
    // 所以这里带过去的历史只是「服务端查不到时」的兜底（见 MAX_HISTORY_TURNS）。
    const history: AssistantHistoryTurn[] = items
      .slice(-MAX_HISTORY_TURNS)
      .map((item) => ({ role: item.role, text: item.text }));
    void dispatch(history, trimmed, true);
  }, [dispatch, items]);

  /** 重发上一次的请求。 */
  const retry = useCallback(() => {
    const attempt = lastAttempt.current;
    if (!attempt || busyRef.current) return;
    void dispatch(attempt.history, attempt.text, false);
  }, [dispatch]);

  /**
   * 接上上次的对话。面板第一次打开时调用一次。
   *
   * <h2>三程</h2>
   * <ol>
   *   <li><b>本地号读得出东西</b> → 用它。这是常态，也是唯一不多发一次请求的路。</li>
   *   <li><b>本地号读回空</b> → 问服务端「我最近在哪个会话里」。覆盖三种「号在、但在这个账号下
   *       什么都不对应」的情况：清过浏览器数据、换了设备、换了账号。</li>
   *   <li><b>任何一程出错</b> → 说出来（{@link applyReplayFailure}），不再只留一行 console.warn。</li>
   * </ol>
   *
   * <h2>为什么本地号优先，而不是「服务端说了算」</h2>
   * 服务端的答案是从**消息表**推的，所以它看不见「刚开、还没说话」的新会话。
   * 若让它说了算，用户点过「新会话」之后只要重开面板，他刚丢掉的那段对话就会自己回来 ——
   * 那恰好否定了「新会话」这个唯一能清空上下文的入口。所以：本地优先，
   * 且服务端兜底要过两道闸（`localActivityRef` 与 {@link NEW_CONVERSATION_STORAGE_KEY}）。
   *
   * <p>拉不到历史**从不阻塞使用**：面板退化成空白，用户照样能发消息 —— 只是这一次空白
   * 会被说明原因，而不是与「新会话」混成同一个样子。
   */
  const loadHistory = useCallback(async () => {
    if (hydratedRef.current) return;
    hydratedRef.current = true;

    let restored: AssistantConversationMessage[];
    try {
      restored = await fetchAssistantConversation(conversationId);
    } catch (error) {
      // 这个 catch 覆盖的东西比它看起来多：除了网络故障，还有「助手没开启」（503），
      // 以及「接口路径写错」「函数没导出」这类**编程错误** —— 后者的表现恰好也是
      // 「历史永远是空的」。全都一声不响地吞掉，就没人会发现面板为什么总是空的。
      console.warn('assistant: 回放会话历史失败', error);
      applyReplayFailure(error);
      return;
    }
    if (restored.length > 0) {
      setItems(restored.map(itemOfHistory));
      return;
    }

    // 本地那个号读不到东西。可能是「这段对话还没有内容」，也可能是「这个号不属于当前账号」——
    // 前端无从区分，也不该自己下结论，问服务端。
    if (readStoredNewConversation()) {
      // 用户主动开的新会话：空就是它该有的样子。这里**不能问**服务端 ——
      // 它看不见一段没有消息的新会话，只会把上一段对话还回来。
      return;
    }
    if (localActivityRef.current || busyRef.current) {
      // 用户已经开始在这里说话了（比如刚发出第一句话，而它还没落库）。
      // 此刻换会话号会把他刚说的话留在被抛弃的那段里。
      return;
    }
    try {
      const latest = await fetchLatestAssistantConversation();
      if (!latest.conversationId || latest.conversationId === conversationId) {
        // `null` = 服务端明确说「你还没有任何对话」。这不是失败，安静地开始就好。
        return;
      }
      setConversationId(latest.conversationId);
      const messages = await fetchAssistantConversation(latest.conversationId);
      if (messages.length > 0) {
        setItems(messages.map(itemOfHistory));
      }
    } catch (error) {
      console.warn('assistant: 查询最近会话失败', error);
      applyReplayFailure(error);
    }
  }, [conversationId, applyReplayFailure]);

  /** 开一段新对话：换会话号并清空本地记录。正在请求时不允许切换（会把结果写进错误的上下文）。 */
  const startNewConversation = useCallback(() => {
    if (busyRef.current) return;
    setConversationId(newConversationId());
    // 这段新会话在服务端眼里还不存在（它从消息表推）。记下这件事，
    // 否则下次打开面板时服务端兜底会把它换成上一段对话 —— 用户的「清空」当场作废。
    storeNewConversation(true);
    localActivityRef.current = true;
    setItems([]);
    setPending(null);
    // 新会话从零开始，上一段对话的裁剪与读回失败都与它无关。
    setTrimmedHistory(null);
    setCompactedHistory(null);
    setHistoryError(null);
    lastAttempt.current = null;
  }, []);

  const decide = useCallback(async (
    action: (pendingActionId: string) => Promise<AssistantTurnResult>,
  ) => {
    const proposal = pending;
    if (!proposal || busyRef.current) return;
    setBusy(true);
    try {
      const result = await action(proposal.pendingActionId);
      // 能走到这里说明服务端已经就这次授权给出结论（成功 / 执行失败 / 引用已失效）。
      // 无论哪一种，这张卡片都作废：留着它只会引诱用户再点一次已经用掉的授权。
      setPending(null);
      applyResult(result);
    } catch (error) {
      const failure = classify(error);
      // 授权彻底死掉（不存在 / 已处理 / 已过期）时才撤卡片；网络类失败保留卡片，让用户能再点。
      if (failure.status === 404 || failure.status === 409 || failure.status === 410) {
        setPending(null);
      }
      applyFailure(error);
    } finally {
      setBusy(false);
    }
  }, [applyFailure, applyResult, pending, setBusy]);

  const confirm = useCallback(() => decide(confirmAssistantAction), [decide]);
  const cancel = useCallback(() => decide(cancelAssistantAction), [decide]);

  return {
    items,
    busy,
    pending,
    unavailable,
    conversationId,
    trimmedHistory,
    compactedHistory,
    historyError,
    send,
    confirm,
    cancel,
    retry,
    loadHistory,
    startNewConversation,
  };
}
