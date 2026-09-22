import { useCallback, useEffect, useRef, useState } from 'react';
import {
  cancelAssistantAction,
  confirmAssistantAction,
  fetchAssistantConversation,
  sendAssistantMessage,
} from '../../api/endpoints';
import type {
  AssistantConversationMessage,
  AssistantHistoryTurn,
  AssistantProposal,
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

const TURN_KINDS: readonly AssistantTurnKind[] = [
  'QUESTION',
  'CONFIRMATION_REQUIRED',
  'EXECUTED',
  'ANSWER',
  'ERROR',
];

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

function isTurnKind(value: unknown): value is AssistantTurnKind {
  return typeof value === 'string' && (TURN_KINDS as readonly string[]).includes(value);
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
    // 服务端已经在 200 里给出了结论（如「引用的待办已经不在了」）：再点一次还是同样的结论。
    retryable: false,
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
 * <h2>会话号：前端生成、前端持久化</h2>
 * 拿到它就等于拿到「上一段对话」，所以它进 `localStorage` —— 刷新页面后接得上。
 * 换会话号（「新会话」）是清空上下文唯一的入口。
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

  const append = useCallback((item: AssistantChatItem) => {
    setItems((prev) => [...prev, item]);
  }, []);

  /** 处理一次 HTTP 层失败：503 时另外记下「功能不可用」。 */
  const applyFailure = useCallback((error: unknown) => {
    const failure = classify(error);
    if (failure.status === 503
      && (failure.code === AssistantCodes.DISABLED || failure.code === AssistantCodes.PROVIDER_UNAVAILABLE)) {
      setUnavailable({ code: failure.code, message: failure.message });
    }
    append({
      id: nextId(),
      role: 'assistant',
      text: failure.message,
      kind: 'ERROR',
      errorCode: failure.code,
      retryable: isRetryable(failure),
    });
  }, [append]);

  /** 处理一轮 200 的响应。 */
  const applyResult = useCallback((result: AssistantTurnResult) => {
    setUnavailable(null);
    // 只在字段**出现**时更新，绝不因为「没看到」而清空：确认 / 取消的响应本来就不带
    // `historyTrim`（它们不走 /messages），按「没看到 = 没裁剪」处理会把提示误清掉。
    if (result.historyTrim) {
      setTrimmedHistory(result.historyTrim.droppedMessages);
    }
    append(itemOf(result));
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
  }, [append]);

  const dispatch = useCallback(async (
    history: AssistantHistoryTurn[],
    text: string,
    appendUser: boolean,
  ) => {
    lastAttempt.current = { history, text };
    if (appendUser) {
      append({ id: nextId(), role: 'user', text });
    }
    setBusy(true);
    try {
      applyResult(await sendAssistantMessage({ conversationId, history, text }));
    } catch (error) {
      applyFailure(error);
    } finally {
      setBusy(false);
    }
  }, [append, applyFailure, applyResult, conversationId, setBusy]);

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
   * 回放当前会话的历史。面板第一次打开时调用一次。
   *
   * 拉不到历史**不报错**：这只是「接上上次」，失败不该让面板变成一个错误页 ——
   * 退化成空白，用户照样能发消息。
   */
  const loadHistory = useCallback(async () => {
    if (hydratedRef.current) return;
    hydratedRef.current = true;
    try {
      const messages = await fetchAssistantConversation(conversationId);
      if (messages.length > 0) {
        setItems(messages.map(itemOfHistory));
      }
    } catch (error) {
      // 拉不到历史不该阻塞使用：退化成空面板，用户照样能发消息。
      //
      // 但要留下痕迹：这个 catch 也覆盖「接口路径写错」「函数没导出」这类**编程错误**，
      // 而它们的表现恰好也是「历史永远是空的」—— 一声不响地吞掉，就没人会发现。
      console.warn('assistant: 回放会话历史失败', error);
    }
  }, [conversationId]);

  /** 开一段新对话：换会话号并清空本地记录。正在请求时不允许切换（会把结果写进错误的上下文）。 */
  const startNewConversation = useCallback(() => {
    if (busyRef.current) return;
    setConversationId(newConversationId());
    setItems([]);
    setPending(null);
    // 新会话从零开始，上一段对话的裁剪与它无关。
    setTrimmedHistory(null);
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
    send,
    confirm,
    cancel,
    retry,
    loadHistory,
    startNewConversation,
  };
}
