import '@testing-library/jest-dom/vitest';
import { App as AntApp } from 'antd';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AssistantPanel } from './AssistantPanel';
import { AssistantLauncher } from './AssistantLauncher';
import { subscribeAssistantEvents } from '../../assistant/assistantEvents';
import type { AssistantEvent } from '../../assistant/assistantEvents';
import { AssistantStreamFailure } from '../../api/assistantStream';
import type { AssistantStreamHandlers } from '../../api/assistantStream';
import type { AssistantTurnResult } from '../../api/types';

const api = vi.hoisted(() => ({
  sendAssistantMessage: vi.fn(),
  confirmAssistantAction: vi.fn(),
  cancelAssistantAction: vi.fn(),
  fetchAssistantConversation: vi.fn(),
  fetchLatestAssistantConversation: vi.fn(),
}));

vi.mock('../../api/endpoints', () => api);

const PENDING_ID = '30000000-0000-0000-0000-000000000003';
const SUMMARY = '删除待办：「和张总确认报价」 2026-09-21';

function renderPanel() {
  return render(<AntApp><AssistantPanel /></AntApp>);
}

// 按名字查「确认 / 取消」这两个按钮必须用正则：antd 会给**恰好两个汉字**的按钮文案
// 中间插一个空格（渲染成「确 认」），字面量匹配会查不到。
const input = () => screen.getByLabelText('给助手的话');
const sendButton = () => screen.getByRole('button', { name: '发送给助手' });

async function ask(text: string) {
  const user = userEvent.setup();
  await user.type(input(), text);
  await user.click(sendButton());
  return user;
}

/** 收到的广播，用来验证「真执行了才广播」。 */
const busEvents: AssistantEvent[] = [];
let unsubscribe: (() => void) | null = null;

beforeEach(() => {
  vi.clearAllMocks();
  // 逐字那一轮的 mock 会**留住**回调与那个还没 resolve 的 promise，而
  // `clearAllMocks` 只清调用记录、不清实现 —— 不额外重置的话，上一轮的实现会漏到下一轮。
  api.sendAssistantMessage.mockReset();
  // 回放是面板挂载时的默认动作：不给默认值，每个用例都会走一遍 catch 分支。
  api.fetchAssistantConversation.mockResolvedValue([]);
  // 本地会话号读回空时会问服务端「我上次在哪个会话里」。默认答「还没有任何对话」——
  // 这正是新用户第一次打开面板时的答案，也就不该有任何历史被拉回来。
  api.fetchLatestAssistantConversation.mockResolvedValue({ conversationId: null });
  // 会话号会写进 localStorage，测试之间不能互相继承。
  window.localStorage.clear();
  busEvents.length = 0;
  unsubscribe = subscribeAssistantEvents((event) => busEvents.push(event));
});

afterEach(() => {
  unsubscribe?.();
  unsubscribe = null;
});

it('starts empty and offers an example instead of an empty box', () => {
  renderPanel();

  expect(screen.getByText('可以直接用一句话交代事情，比如：')).toBeVisible();
  expect(screen.getByText('帮我记一下明天下午三点和张总确认报价')).toBeVisible();
  expect(input()).toBeEnabled();
  // 还没发生任何事，就不该有任何「结果」类文案出现。
  expect(screen.queryByText('已执行')).not.toBeInTheDocument();
  expect(screen.queryByText('失败')).not.toBeInTheDocument();
});

it('shows the follow-up question and keeps the input usable', async () => {
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'QUESTION',
    message: '这条待办放在哪一天？',
    missing: ['date'],
  });
  renderPanel();

  await ask('记一条待办');

  expect(await screen.findByText('这条待办放在哪一天？')).toBeVisible();
  // 追问之后用户必须还能接着答 —— 这正是一次追问的意义。
  expect(input()).toBeEnabled();
  expect(screen.getByText('需要补充信息')).toBeVisible();

  // 请求里只带用户原话，不带任何身份字段：身份只由服务端从认证上下文取。
  expect(api.sendAssistantMessage).toHaveBeenCalledTimes(1);
  const request = api.sendAssistantMessage.mock.calls[0][0];
  expect(request.text).toBe('记一条待办');
  expect(request).not.toHaveProperty('userId');
  expect(request).not.toHaveProperty('user');
});

it('renders the confirmation card with the title and the date', async () => {
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'CONFIRMATION_REQUIRED',
    message: `请确认后我再执行：${SUMMARY}`,
    proposal: {
      pendingActionId: PENDING_ID,
      tool: 'todo.delete',
      summary: SUMMARY,
      arguments: { todoId: 'todo-quote' },
    },
  });
  renderPanel();

  await ask('删掉和张总确认报价那条');

  const summary = await screen.findByText(SUMMARY);
  expect(summary).toBeVisible();
  // 「是哪一条待办」只能靠标题 + 日期共同消解歧义，缺一个这张卡片就不够用户下判断。
  expect(summary.textContent).toContain('和张总确认报价');
  expect(summary.textContent).toContain('2026-09-21');

  expect(screen.getByRole('button', { name: /确\s*认/ })).toBeVisible();
  expect(screen.getByRole('button', { name: /取\s*消/ })).toBeVisible();
  // 卡片是来征求授权的，此刻什么都没执行 —— 不能说成已执行。
  expect(screen.getByText('尚未执行')).toBeVisible();
  expect(screen.queryByText('已执行')).not.toBeInTheDocument();
  expect(busEvents).toHaveLength(0);
});

it('shows what will change and what it was before, side by side', async () => {
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'CONFIRMATION_REQUIRED',
    message: '请确认后我再执行：修改待办：「和张总确认报价」 → 时间改为 2026-09-24',
    proposal: {
      pendingActionId: PENDING_ID,
      tool: 'todo.update',
      summary: '修改待办：「和张总确认报价」 → 时间改为 2026-09-24',
      changes: [{ field: 'when', label: '时间', before: '2026-09-22 15:00', after: '2026-09-24' }],
      arguments: { todoId: 'todo-quote', date: '2026-09-24' },
    },
  });
  renderPanel();

  await ask('把和张总确认报价那条改到 9 月 24 号');

  const changes = await screen.findByTestId('assistant-proposal-changes');
  // 两栏缺一不可：只有「改成什么」看不出模型以为的当前值是什么，而那才是用户要核对的东西。
  expect(changes).toHaveTextContent('时间');
  expect(changes).toHaveTextContent('2026-09-22 15:00');
  expect(changes).toHaveTextContent('2026-09-24');
});

it('never renders an unknown before as a blank', async () => {
  // 空白会被读成「没有变化」，与服务端的本意相反 —— 它是**没拿到**「改前」的证据。
  // 这个区别是这张卡片能不能被信任的分界线：编出来的「改前」用户会当成事实去核对。
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'CONFIRMATION_REQUIRED',
    message: '请确认后我再执行：修改待办：「和张总确认报价」 → 备注改为「已确认报价」',
    proposal: {
      pendingActionId: PENDING_ID,
      tool: 'todo.update',
      summary: '修改待办：「和张总确认报价」 → 备注改为「已确认报价」',
      changes: [{ field: 'note', label: '备注', before: null, after: '已确认报价' }],
      arguments: { todoId: 'todo-quote', note: '已确认报价' },
    },
  });
  renderPanel();

  await ask('给它加个备注：已确认报价');

  const changes = await screen.findByTestId('assistant-proposal-changes');
  expect(changes).toHaveTextContent('当前未知');
  expect(changes).toHaveTextContent('已确认报价');
});

it('renders no change block for an action that has no before', async () => {
  // 新建 / 删除没有「改前」可言：`changes` 为空时不该在卡片上留一个空区块。
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'CONFIRMATION_REQUIRED',
    message: `请确认后我再执行：${SUMMARY}`,
    proposal: {
      pendingActionId: PENDING_ID,
      tool: 'todo.delete',
      summary: SUMMARY,
      changes: [],
      arguments: { todoId: 'todo-quote' },
    },
  });
  renderPanel();

  await ask('删掉和张总确认报价那条');

  await screen.findByText(SUMMARY);
  expect(screen.queryByTestId('assistant-proposal-changes')).not.toBeInTheDocument();
});

it('renders a long summary as a wrapped body instead of one squashed line', async () => {
  // 发邮件这类动作的正文是长文本（列已升为 text）。它必须换行显示 ——
  // 压成一行会让用户根本读不完就点了确认。
  const body = '张总您好，关于上周的报价我们做了一些更新，具体如下：'.repeat(3);
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'CONFIRMATION_REQUIRED',
    message: `请确认后我再执行：${body}`,
    proposal: {
      pendingActionId: PENDING_ID,
      tool: 'todo.update',
      summary: body,
      arguments: { todoId: 'todo-quote' },
    },
  });
  renderPanel();

  await ask('把这些写进备注');

  const summary = await screen.findByText(body);
  expect(summary).toBeVisible();
});

it('confirming calls the confirm endpoint with the pending action id', async () => {
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'CONFIRMATION_REQUIRED',
    message: `请确认后我再执行：${SUMMARY}`,
    proposal: { pendingActionId: PENDING_ID, tool: 'todo.delete', summary: SUMMARY },
  });
  api.confirmAssistantAction.mockResolvedValue({
    kind: 'EXECUTED',
    message: '已删除：和张总确认报价 2026-09-21',
  });
  renderPanel();

  const user = await ask('删掉和张总确认报价那条');
  await user.click(await screen.findByRole('button', { name: /确\s*认/ }));

  // 只传 pendingActionId：参数以服务端落库的那一份为准，前端回传参数会让「授权落库」白做。
  await waitFor(() => expect(api.confirmAssistantAction).toHaveBeenCalledWith(PENDING_ID));
  expect(api.confirmAssistantAction.mock.calls[0]).toHaveLength(1);

  expect(await screen.findByText('已删除：和张总确认报价 2026-09-21')).toBeVisible();
  expect(screen.getByText('已执行')).toBeVisible();
  // 卡片作废：留着它会让用户再点一次已经用掉的授权。
  expect(screen.queryByRole('button', { name: /确\s*认/ })).not.toBeInTheDocument();
  // 真的执行成功才广播，别的页面（待办日历）据此重读。
  expect(busEvents).toHaveLength(1);
});

it('cancelling calls the cancel endpoint and executes nothing', async () => {
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'CONFIRMATION_REQUIRED',
    message: `请确认后我再执行：${SUMMARY}`,
    proposal: { pendingActionId: PENDING_ID, tool: 'todo.delete', summary: SUMMARY },
  });
  api.cancelAssistantAction.mockResolvedValue({
    kind: 'ANSWER',
    message: '已取消，未做任何改动',
  });
  renderPanel();

  const user = await ask('删掉和张总确认报价那条');
  await user.click(await screen.findByRole('button', { name: /取\s*消/ }));

  await waitFor(() => expect(api.cancelAssistantAction).toHaveBeenCalledWith(PENDING_ID));
  expect(api.confirmAssistantAction).not.toHaveBeenCalled();
  expect(await screen.findByText('已取消，未做任何改动')).toBeVisible();
  expect(screen.queryByRole('button', { name: /确\s*认/ })).not.toBeInTheDocument();
  // 取消什么都没改，不该让别的页面白跑一次刷新。
  expect(busEvents).toHaveLength(0);
});

it('a failed confirmation never reads as success', async () => {
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'CONFIRMATION_REQUIRED',
    message: `请确认后我再执行：${SUMMARY}`,
    proposal: { pendingActionId: PENDING_ID, tool: 'todo.delete', summary: SUMMARY },
  });
  api.confirmAssistantAction.mockRejectedValue({
    response: {
      status: 503,
      data: { code: 'ASSISTANT_UNAVAILABLE', message: '模型供应商暂时不可达' },
    },
  });
  renderPanel();

  const user = await ask('删掉和张总确认报价那条');
  await user.click(await screen.findByRole('button', { name: /确\s*认/ }));

  expect(await screen.findByText('失败')).toBeVisible();
  // 失败原因原样留在对话里（服务端的那句话），而不是一个 3 秒就消失的提示。
  expect(screen.getByText('模型供应商暂时不可达')).toBeVisible();
  expect(screen.getByText('模型服务暂时不可用')).toBeVisible();
  // 关键：不能出现任何「做成了」的痕迹。
  expect(screen.queryByText('已执行')).not.toBeInTheDocument();
  expect(screen.queryByText(/^已删除/)).not.toBeInTheDocument();
  // 也没有任何东西被执行过，所以不该广播。
  expect(busEvents).toHaveLength(0);
  // 网络类失败保留卡片，用户可以再点一次；重试按钮就在失败原因旁边。
  expect(screen.getByRole('button', { name: /确\s*认/ })).toBeEnabled();
  expect(screen.getByRole('button', { name: /重\s*试/ })).toBeVisible();
});

it('reports the feature as unavailable when the server answers 503', async () => {
  api.sendAssistantMessage.mockRejectedValue({
    response: {
      status: 503,
      data: { code: 'ASSISTANT_DISABLED', message: '助手功能未开启，请联系管理员配置' },
    },
  });
  renderPanel();

  await ask('记一条待办');

  expect(await screen.findByText('AI 助手未开启')).toBeVisible();
  // 服务端那句话会出现两次：一次在顶部的不可用提示里，一次作为这一轮失败的原话留在对话里。
  expect(screen.getAllByText('助手功能未开启，请联系管理员配置')).toHaveLength(2);
  // 功能没开就没必要让人继续打字：先禁用，而不是让他打完再说一声不行。
  expect(input()).toBeDisabled();
  expect(sendButton()).toBeDisabled();
  expect(screen.queryByText('已执行')).not.toBeInTheDocument();
});

it('opens the conversation panel from the global floating button', async () => {
  render(<AntApp><AssistantLauncher /></AntApp>);
  const user = userEvent.setup();

  await user.click(screen.getByRole('button', { name: 'AI 助手' }));

  // 面板是全局唤起能力的入口：任何页面上它都得能开。
  expect(await screen.findByTestId('assistant-panel')).toBeVisible();
});

// ---------- 会话落地：刷新/重开面板后接得上上次 ----------

it('replays the stored conversation on open, keeping each turn outcome', async () => {
  // 这两句刻意**不**用空面板示例里的文案：示例会先被渲染出来，用示例文案断言
  // 会在「示例被历史替换掉」的瞬间变成 not in the document —— 测的就不是回放了。
  api.fetchAssistantConversation.mockResolvedValue([
    { role: 'user', text: '把李总那份合同标记完成' },
    { role: 'assistant', kind: 'EXECUTED', text: '已完成：李总那份合同' },
  ]);
  renderPanel();

  // 刷新后最要紧的是「上次说到哪了」：消息与它**当时的终点**都要回来，
  // 否则用户拿到的是一堆分不清成没成的气泡。
  expect(await screen.findByText('把李总那份合同标记完成')).toBeVisible();
  expect(screen.getByText('已完成：李总那份合同')).toBeVisible();
  expect(screen.getByText('已执行')).toBeVisible();
  // 回放的是历史，不是刚发生的事：不该重新广播「数据变了」。
  expect(busEvents).toHaveLength(0);
});

it('does not resurrect a confirmation card from history', async () => {
  api.fetchAssistantConversation.mockResolvedValue([
    { role: 'user', text: '删掉和张总确认报价那条' },
    { role: 'assistant', kind: 'CONFIRMATION_REQUIRED', text: `请确认后我再执行：${SUMMARY}` },
  ]);
  renderPanel();

  expect(await screen.findByText(`请确认后我再执行：${SUMMARY}`)).toBeVisible();
  // 那次授权大概率已经确认 / 取消 / 过期。把按钮画出来，等于引诱用户去点一个注定失败的键。
  expect(screen.queryByRole('button', { name: /确\s*认/ })).not.toBeInTheDocument();
  expect(screen.getByText('当时待确认')).toBeVisible();
});

it('a failed replay leaves the panel usable and says the history was not read', async () => {
  const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
  api.fetchAssistantConversation.mockRejectedValue(new Error('boom'));
  api.sendAssistantMessage.mockResolvedValue({ kind: 'ANSWER', message: '好的' });
  renderPanel();

  await ask('你好');

  // 拉不到历史不该把面板变成一个错误页：退化成空白，用户照样能发消息。
  expect(await screen.findByText('好的')).toBeVisible();
  expect(input()).toBeEnabled();
  // 回放失败不是「这一轮失败了」：对话里不该因此多出一条失败消息。
  expect(screen.queryByText('失败')).not.toBeInTheDocument();
  // 但**必须在界面上出现**。以前它只留一行 console.warn，而「只留痕在人看不见的地方」
  // 恰好让「助手没开启」与「他刚开了一段新会话」在屏幕上一模一样。
  expect(screen.getByTestId('assistant-history-unavailable')).toBeVisible();
  // 留痕仍然要有：否则「接口写错」这类编程错误会表现成「历史永远是空的」而无人发现。
  expect(warn).toHaveBeenCalled();
  warn.mockRestore();
});

it('explains a blank panel when the assistant is not enabled', async () => {
  api.fetchAssistantConversation.mockRejectedValue({
    response: {
      status: 503,
      data: { code: 'ASSISTANT_DISABLED', message: '助手功能未开启，请联系管理员配置' },
    },
  });
  renderPanel();

  // 这一屏以前是纯空白，与「他刚点了新会话」没有任何区别 —— 用户只会以为助手坏了。
  expect(await screen.findByText('AI 助手未开启')).toBeVisible();
  // 功能没开就别让他继续打字。
  expect(input()).toBeDisabled();
  // 功能都没开，再问「我上次在哪个会话里」只是明知故问。
  expect(api.fetchLatestAssistantConversation).not.toHaveBeenCalled();
});

it('sends the conversation id so the server can group the trail', async () => {
  api.sendAssistantMessage.mockResolvedValue({ kind: 'ANSWER', message: '好的' });
  renderPanel();

  await ask('你好');

  const request = api.sendAssistantMessage.mock.calls[0][0];
  expect(typeof request.conversationId).toBe('string');
  expect(request.conversationId).toMatch(/^[0-9a-f-]{36}$/);
  // 回放用的是同一个会话号，否则「接上上次」接的根本不是同一段对话。
  expect(api.fetchAssistantConversation).toHaveBeenCalledWith(request.conversationId);
});

it('keeps the same conversation id after a remount, so a refresh continues the thread', async () => {
  api.sendAssistantMessage.mockResolvedValue({ kind: 'ANSWER', message: '好的' });
  const first = renderPanel();
  await ask('你好');
  const firstId = api.sendAssistantMessage.mock.calls[0][0].conversationId;
  first.unmount();

  // 重新挂载 ≈ 刷新页面。会话号必须还是那一个 —— 这正是这一阶段要买到的东西。
  renderPanel();
  await ask('还在吗');

  expect(api.sendAssistantMessage.mock.calls[1][0].conversationId).toBe(firstId);
});

it('starting a new conversation switches the id and clears the transcript', async () => {
  api.fetchAssistantConversation.mockResolvedValue([
    { role: 'assistant', kind: 'ANSWER', text: '很久以前的回答' },
  ]);
  api.sendAssistantMessage.mockResolvedValue({ kind: 'ANSWER', message: '好的' });
  renderPanel();
  const user = userEvent.setup();
  expect(await screen.findByText('很久以前的回答')).toBeVisible();
  const oldId = api.fetchAssistantConversation.mock.calls[0][0];

  await user.click(screen.getByRole('button', { name: /新\s*会\s*话/ }));

  // 旧对话不该继续占着屏幕 —— 「新会话」的全部意义就是换一段上下文。
  expect(screen.queryByText('很久以前的回答')).not.toBeInTheDocument();
  expect(screen.getByText('可以直接用一句话交代事情，比如：')).toBeVisible();

  await ask('你好');
  expect(api.sendAssistantMessage.mock.calls[0][0].conversationId).not.toBe(oldId);
  // 换了会话号也不该回头去拉旧会话的历史。
  expect(api.fetchAssistantConversation).toHaveBeenCalledTimes(1);
});

// ---------- 语境被裁剪：「我已经记不住前面了」必须说出来 ----------

it('says so when the earlier conversation is no longer in context', async () => {
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'ANSWER',
    message: '好的',
    historyTrim: { droppedMessages: 3 },
  });
  renderPanel();

  await ask('接着上面那条继续说');

  // 这个提示不是装饰。上下文被裁掉之后，模型不但失去了那段对话，还**不知道自己失去了**，
  // 于是会拿着断掉的开头照常作答 —— 用户看不到任何异常。这是唯一能让他知道的地方。
  const note = await screen.findByTestId('assistant-history-trim-note');
  expect(note).toBeVisible();
  expect(note).toHaveTextContent('3');
});

it('keeps the trim notice when a later response carries no trim information', async () => {
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'ANSWER',
    message: '好的',
    historyTrim: { droppedMessages: 2 },
  });
  renderPanel();
  await ask('第一条');
  expect(await screen.findByTestId('assistant-history-trim-note')).toBeVisible();

  // 第二轮**不带** historyTrim（服务端一条没丢时就会省略它）。
  // 若按「没看到 = 没裁剪」把提示清掉，用户会以为助手又全都记得了 —— 而它并没有。
  // 注意这条与「确认 / 取消的响应不带该字段」是同一个坑（见 useAssistant.applyResult）。
  api.sendAssistantMessage.mockResolvedValue({ kind: 'ANSWER', message: '接着答' });
  await ask('第二条');

  expect(screen.getByTestId('assistant-history-trim-note')).toBeVisible();
});

it('clears the trim notice when starting a new conversation', async () => {
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'ANSWER',
    message: '好的',
    historyTrim: { droppedMessages: 1 },
  });
  renderPanel();
  await ask('你好');
  expect(await screen.findByTestId('assistant-history-trim-note')).toBeVisible();

  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: /新\s*会\s*话/ }));

  // 新会话从零开始，上一段对话的裁剪与它无关 —— 留着那句提示会变成纯粹的误导。
  expect(screen.queryByTestId('assistant-history-trim-note')).not.toBeInTheDocument();
});

it('keeps the compaction notice distinct from trim and until a new conversation', async () => {
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'ANSWER',
    message: '好的',
    historyTrim: { droppedMessages: 2 },
    historyCompaction: { summarizedMessages: 12 },
  });
  renderPanel();
  await ask('继续之前的讨论');

  expect(await screen.findByTestId('assistant-history-compaction-note')).toHaveTextContent('12');
  expect(screen.getByTestId('assistant-history-trim-note')).toHaveTextContent('2');

  api.sendAssistantMessage.mockResolvedValue({ kind: 'ANSWER', message: '继续' });
  await ask('继续');
  expect(screen.getByTestId('assistant-history-compaction-note')).toBeVisible();

  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: /新\s*会\s*话/ }));
  expect(screen.queryByTestId('assistant-history-compaction-note')).not.toBeInTheDocument();
  expect(screen.queryByTestId('assistant-history-trim-note')).not.toBeInTheDocument();
});

// ---------- 会话号的兜底：浏览器忘了，服务端还记得 ----------
//
// 记录一直在库里（`assistant_conversation_messages`），前端丢的从来只是那个号。
// 这几条钉住的是「什么时候去问服务端」以及**什么时候不许问**。

const REMEMBERED = '40000000-0000-0000-0000-000000000004';

/** 只有被问到的那个会话号有内容，其余都空 —— 用来分辨「屏幕上这段是哪来的」。 */
function replayOnly(conversationId: string, messages: unknown[]) {
  api.fetchAssistantConversation.mockImplementation((id: string) =>
    Promise.resolve(id === conversationId ? messages : []));
}

it('falls back to the conversation the server remembers when the local one has nothing', async () => {
  // 清过浏览器数据 / 换了设备 / 换了账号：本地没有号，或者那个号在这个账号下什么都不对应。
  replayOnly(REMEMBERED, [{ role: 'assistant', kind: 'ANSWER', text: '上次说的那件事' }]);
  api.fetchLatestAssistantConversation.mockResolvedValue({ conversationId: REMEMBERED });
  renderPanel();

  expect(await screen.findByText('上次说的那件事')).toBeVisible();
  // 只问一次：它是兜底，不是每开一次面板就查一遍的东西。
  expect(api.fetchLatestAssistantConversation).toHaveBeenCalledTimes(1);
});

it('asks the server nothing when the local conversation still has content', async () => {
  api.fetchAssistantConversation.mockResolvedValue([
    { role: 'assistant', kind: 'ANSWER', text: '就在本地这段里' },
  ]);
  renderPanel();

  expect(await screen.findByText('就在本地这段里')).toBeVisible();
  // 常态路径多发一次请求换不来任何东西 —— 本地号优先，兜底只在它读空时才走。
  expect(api.fetchLatestAssistantConversation).not.toHaveBeenCalled();
});

it('never overwrites a conversation the user started on purpose', async () => {
  // 两次挂载之间 localStorage 保留着，等于「点过新会话，关掉面板，再打开」。
  replayOnly(REMEMBERED, [{ role: 'assistant', kind: 'ANSWER', text: '很久以前的回答' }]);
  api.fetchLatestAssistantConversation.mockResolvedValue({ conversationId: REMEMBERED });

  const first = renderPanel();
  expect(await screen.findByText('很久以前的回答')).toBeVisible();
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: /新\s*会\s*话/ }));
  expect(screen.queryByText('很久以前的回答')).not.toBeInTheDocument();
  first.unmount();

  renderPanel();

  // 服务端看不见一段「刚开、还没说话」的新会话（它从消息表推），问它只会把用户刚丢掉的
  // 那段还回来。所以这里**必须不问** —— 否则重开面板就撤销了用户唯一那个清空上下文的动作。
  expect(screen.getByText('可以直接用一句话交代事情，比如：')).toBeVisible();
  expect(screen.queryByText('很久以前的回答')).not.toBeInTheDocument();
  expect(api.fetchLatestAssistantConversation).toHaveBeenCalledTimes(1);
});

// ---------- 逐字回答：变的是一条记录，不是消息在变 ----------
//
// `sendAssistantMessage` 现在多收一组回调，在结论到达之前先报进度与片段。
// 协议与传输细节在 `assistantStream.test.ts`，这里钉的只有**屏幕上的样子**。

const ANSWERED: AssistantTurnResult = {
  kind: 'ANSWER',
  message: '张总那边我看过了，明天下午三点那条报价还没确认。',
};

/**
 * 一轮**停住**的流：回调交给用例，结论什么时候到也由用例决定。
 *
 * 不这样就没法断言「还没结束的时候屏幕上是什么」，而那正是这一阶段要买到的东西 ——
 * 只让 mock 立刻 resolve 的话，中间那些状态一个都看不见。
 */
function holdingTurn() {
  let streamed: AssistantStreamHandlers = {};
  let release: (result: AssistantTurnResult) => void = () => undefined;
  api.sendAssistantMessage.mockImplementation(
    async (_request: unknown, handlers: AssistantStreamHandlers) => {
      streamed = handlers;
      return new Promise<AssistantTurnResult>((resolve) => { release = resolve; });
    },
  );
  return {
    // setState 要发生在 act 里，否则 React 会警告「更新没有包在 act 中」。
    push: async (change: (handlers: AssistantStreamHandlers) => void) => {
      await act(async () => change(streamed));
    },
    finish: async (result: AssistantTurnResult) => {
      await act(async () => release(result));
    },
  };
}

it('shows the answer growing before the turn is over', async () => {
  const turn = holdingTurn();
  renderPanel();

  await ask('张总的报价确认了吗');

  // 一开始就在动：占位记录承担的是「请求已经出去了」这个事实。
  expect(await screen.findByText('正在想…')).toBeVisible();

  await turn.push((handlers) => handlers.onStatus?.({
    type: 'status',
    state: 'reading',
    tool: 'conversation.search',
  }));
  expect(screen.getByText('正在查资料…')).toBeVisible();
  // 工具名是内部标识，不给用户看：他此刻要判断的只有「它还在动，那就等」。
  expect(screen.queryByText(/conversation\.search/)).not.toBeInTheDocument();

  await turn.push((handlers) => handlers.onDelta?.('张总那边'));
  expect(screen.getByText('张总那边')).toBeVisible();
  // 正文一到，进度就退场（它是替代品，不是并列的一行）。
  expect(screen.queryByText('正在查资料…')).not.toBeInTheDocument();
  // 结论还没到 ⇒ 一个结论标签都不许有：成功 / 失败 / 待确认此刻都还不知道。
  expect(screen.queryByText('失败')).not.toBeInTheDocument();
  expect(screen.queryByText('已执行')).not.toBeInTheDocument();

  await turn.finish(ANSWERED);

  expect(await screen.findByText(ANSWERED.message)).toBeVisible();
  // 覆盖，不是追加：屏幕上不该同时留着那半截草稿。
  expect(screen.queryByText('张总那边')).not.toBeInTheDocument();
  expect(screen.queryByTestId('assistant-streaming')).not.toBeInTheDocument();
});

it('drops what was already shown when the model starts over', async () => {
  const turn = holdingTurn();
  renderPanel();
  await ask('张总的报价确认了吗');

  await turn.push((handlers) => handlers.onDelta?.('张总那边我看'));
  expect(screen.getByText('张总那边我看')).toBeVisible();

  await turn.push((handlers) => handlers.onReset?.());
  // 重来意味着前一段草稿不再是最终回答的前缀：留在屏幕上就是一句假话。
  expect(screen.queryByText('张总那边我看')).not.toBeInTheDocument();
  expect(screen.getByText('正在重新回答…')).toBeVisible();

  await turn.push((handlers) => handlers.onDelta?.('我不确定'));
  expect(screen.getByText('我不确定')).toBeVisible();

  await turn.finish({ kind: 'ANSWER', message: '我不确定' });
  expect(await screen.findByText('我不确定')).toBeVisible();
});

it('never leaves half an answer on screen when the stream breaks', async () => {
  api.sendAssistantMessage.mockImplementation(
    async (_request: unknown, handlers: AssistantStreamHandlers) => {
      handlers.onDelta?.('张总那边我看');
      throw new AssistantStreamFailure(
        '助手的回答中途断了，这一轮的结果未知，再发一次通常就好了',
        { reachedServer: false },
      );
    },
  );
  renderPanel();

  await ask('张总的报价确认了吗');

  expect(await screen.findByText('失败')).toBeVisible();
  // 半句话必须消失：它会被当成整句话读，而这一轮的结果其实是未知的。
  expect(screen.queryByText('张总那边我看')).not.toBeInTheDocument();
  expect(screen.queryByTestId('assistant-streaming')).not.toBeInTheDocument();
  expect(screen.getByText(/结果未知/)).toBeVisible();
  // 「这一轮做了什么」都不知道 ⇒ 值得重试。判据现在来自那个失败对象，不再来自 HTTP 状态码。
  expect(screen.getByRole('button', { name: /重\s*试/ })).toBeVisible();
});

it('a provider failure that arrives inside the stream still offers a retry', async () => {
  // 以前它是一条 HTTP 503，现在是一条 200 里的 `final{kind=ERROR}`。
  // 「能不能重试」必须跟着这个变化走 —— 否则用户会失去唯一那个能自救的按钮。
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'ERROR',
    errorCode: 'ASSISTANT_UNAVAILABLE',
    message: '模型供应商暂时不可达',
  });
  renderPanel();

  await ask('张总的报价确认了吗');

  expect(await screen.findByText('失败')).toBeVisible();
  expect(screen.getByText('模型供应商暂时不可达')).toBeVisible();
  // 顶部那条横幅是同一件事的另一处表达：不是你的话有问题，是对面暂时不行。
  expect(screen.getByText('模型服务暂时不可用')).toBeVisible();
  expect(screen.getByRole('button', { name: /重\s*试/ })).toBeVisible();
  // 关键：这个形状里没有「做成了」的任何痕迹。
  expect(screen.queryByText('已执行')).not.toBeInTheDocument();
});

it('does not offer a retry for a conclusion the server already made', async () => {
  // 反过来的一面同样要钉住：服务端已经想清楚了（这条待办不在了），
  // 再点一次还是同一句话 —— 给一个「重试」按钮只是在浪费用户的时间。
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'ERROR',
    errorCode: 'TODO_NOT_FOUND',
    message: '这条待办已经不在了',
  });
  renderPanel();

  await ask('把和张总确认报价标记完成');

  expect(await screen.findByText('这条待办已经不在了')).toBeVisible();
  expect(screen.queryByRole('button', { name: /重\s*试/ })).not.toBeInTheDocument();
});

it('keeps the conversation usable after a stream of deltas', async () => {
  const turn = holdingTurn();
  renderPanel();
  await ask('张总的报价确认了吗');
  await turn.push((handlers) => handlers.onDelta?.('张总那边'));
  await turn.finish(ANSWERED);

  // 逐字那一轮结束后，这一段对话与以前一模一样地能用：下一轮带上它。
  api.sendAssistantMessage.mockResolvedValue({ kind: 'ANSWER', message: '好' });
  await ask('那就明天再问');

  const request = api.sendAssistantMessage.mock.calls[1][0] as {
    history: { role: string; text: string }[];
  };
  expect(request.history).toContainEqual({ role: 'assistant', text: ANSWERED.message });
});
