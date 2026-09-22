import '@testing-library/jest-dom/vitest';
import { App as AntApp } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AssistantPanel } from './AssistantPanel';
import { AssistantLauncher } from './AssistantLauncher';
import { subscribeAssistantEvents } from '../../assistant/assistantEvents';
import type { AssistantEvent } from '../../assistant/assistantEvents';

const api = vi.hoisted(() => ({
  sendAssistantMessage: vi.fn(),
  confirmAssistantAction: vi.fn(),
  cancelAssistantAction: vi.fn(),
  fetchAssistantConversation: vi.fn(),
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
  // 回放是面板挂载时的默认动作：不给默认值，每个用例都会走一遍 catch 分支。
  api.fetchAssistantConversation.mockResolvedValue([]);
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

it('a failed replay leaves the panel usable instead of turning it into an error page', async () => {
  const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
  api.fetchAssistantConversation.mockRejectedValue(new Error('boom'));
  api.sendAssistantMessage.mockResolvedValue({ kind: 'ANSWER', message: '好的' });
  renderPanel();

  await ask('你好');

  expect(await screen.findByText('好的')).toBeVisible();
  expect(input()).toBeEnabled();
  // 回放失败不是「这一轮失败了」：对话里不该因此多出一条失败消息。
  expect(screen.queryByText('失败')).not.toBeInTheDocument();
  // 但要留痕：否则「接口写错」这类编程错误会表现成「历史永远是空的」而无人发现。
  expect(warn).toHaveBeenCalled();
  warn.mockRestore();
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
