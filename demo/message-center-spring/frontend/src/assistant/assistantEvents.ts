/**
 * 助手与页面之间的极简事件总线（本期只有一个事件）。
 *
 * <h2>为什么需要它</h2>
 * `TodoCalendarPage` 是 `useState` + 手动 `fetchTodos()`，不走 react-query，也没有全局 store。
 * 于是助手**在别的页面**改了待办时，已经打开着的待办页不会自己更新 —— 用户刚对助手说
 * 「帮我标记完成 xx」，切到待办日历却还看得见那条，会以为是助手骗了他。
 *
 * <h2>为什么不用 react-query / 全局状态</h2>
 * 把 `TodoCalendarPage` 迁到 react-query、或引入全局状态，都会放大本次改动的回归面，
 * 那是另一件事。这里只需要一个「发生了改变」的信号，约 20 行就够。
 *
 * <h2>事件为什么不带载荷</h2>
 * 订阅方要的是「该重读了」，而不是「变成了什么」。带上载荷就要求发布方知道订阅方的数据形状，
 * 于是两个本不该耦合的模块粘在一起；而且载荷一定会在某次改动里变得不可信 ——
 * 与其同步一份可能过期的数据，不如让订阅方去读唯一真源。
 */

export type AssistantEvent = {
  /** 助手的动作**已经执行成功**。只有真的落地了才发，待确认、取消、失败都不发。 */
  type: 'assistant-action-executed';
};

type AssistantEventListener = (event: AssistantEvent) => void;

const listeners = new Set<AssistantEventListener>();

/** 订阅。返回值就是退订函数，可以直接作为 `useEffect` 的清理函数。 */
export function subscribeAssistantEvents(listener: AssistantEventListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * 发布。
 *
 * 逐个 try/catch，并先复制一份监听器列表：一次**已经成功**的动作不能因为某个订阅方渲染抛错，
 * 就在调用方那里变成失败（那正是「不得把失败静默成成功」的镜像 —— 也不该把成功静默成失败）；
 * 复制一份是因为监听器可能在回调里退订，直接遍历会漏掉后面的监听器。
 */
export function publishAssistantEvent(event: AssistantEvent): void {
  for (const listener of [...listeners]) {
    try {
      listener(event);
    } catch {
      // 订阅方的问题不该影响已经执行完成的动作，也不该影响其他订阅方。
    }
  }
}
