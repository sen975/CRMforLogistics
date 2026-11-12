import '@testing-library/jest-dom/vitest';
import { App as AntApp } from 'antd';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { AssistantPanel } from './AssistantPanel';

/**
 * 皮肤**挂载点**的回归。
 *
 * <p>面板的取色从「antd token 内联样式」改成了 class（皮肤在 `messageCenterTheme.css`
 * 的 `.mc-assistant-drawer` 段里）。这次改动的风险不是「会不会报错」——内联样式写错了
 * 顶多是颜色不对；class 名写错了同样不报错，但会在**样式表里整段失效**，而且没人发现。
 * 所以这里把「哪种消息挂哪个 class」钉死：改名、漏挂、挂错，立刻红。
 *
 * <p>视觉对不对由 CDP 真渲染负责（`docs/ui-mockups/assistant-shots/`），这里只管接线。
 */

const api = vi.hoisted(() => ({
  sendAssistantMessage: vi.fn(),
  confirmAssistantAction: vi.fn(),
  cancelAssistantAction: vi.fn(),
  fetchAssistantConversation: vi.fn(),
  fetchAssistantConversations: vi.fn(),
  fetchLatestAssistantConversation: vi.fn(),
  createAssistantConversation: vi.fn(),
  openAssistantConversation: vi.fn(),
  deleteAssistantConversation: vi.fn(),
  fetchTemplateMediaUpload: vi.fn(),
  uploadTemplateMedia: vi.fn(),
}));
/** 上传链路整体顶掉：本文件不验它，但它必须在（面板 import 了它）。 */
const tplMedia = vi.hoisted(() => ({ recoverTemplateMediaUpload: vi.fn() }));

vi.mock('../templates/templateMediaUpload', () => tplMedia);
vi.mock('../../api/endpoints', () => api);

const PENDING_ID = '30000000-0000-0000-0000-000000000003';

const input = () => screen.getByLabelText('给助手的话');

function renderPanel() {
  return render(<AntApp><AssistantPanel /></AntApp>);
}

async function ask(text: string) {
  const user = userEvent.setup();
  await user.type(input(), text);
  await user.click(screen.getByRole('button', { name: '发送给助手' }));
}

beforeEach(() => {
  vi.clearAllMocks();
  api.sendAssistantMessage.mockReset();
  tplMedia.recoverTemplateMediaUpload.mockReset();
  // 回放是面板挂载时的默认动作，不给默认值每个用例都会走一遍 catch 分支。
  api.fetchAssistantConversation.mockResolvedValue([]);
  api.fetchAssistantConversations.mockResolvedValue([]);
  api.fetchLatestAssistantConversation.mockResolvedValue({ conversationId: null });
  window.localStorage.clear();
});

it('把空态示例渲染成可点 chip，点一下就填进输入框', async () => {
  const { container } = renderPanel();

  const chips = container.querySelectorAll('.as-example');
  expect(chips).toHaveLength(3);
  expect(chips[0].tagName).toBe('BUTTON');

  await userEvent.setup().click(chips[0]);

  expect(input()).toHaveValue(chips[0].textContent);
});

it('用户消息与助手回答各挂各的气泡 class', async () => {
  api.sendAssistantMessage.mockResolvedValue({ kind: 'EXECUTED', message: '已完成：李总那份合同' });
  const { container } = renderPanel();

  await ask('把李总那份合同标记完成');

  expect(await screen.findByText('已完成：李总那份合同')).toBeVisible();
  expect(container.querySelector('.as-bubble.is-user')?.textContent).toBe('把李总那份合同标记完成');
  expect(container.querySelector('.as-bubble.is-assistant')?.textContent).toBe('已完成：李总那份合同');
  expect(container.querySelector('.as-tag.is-ok')?.textContent).toBe('已执行');
});

it('失败回答挂错误气泡与失败 chip', async () => {
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'ERROR',
    message: '那条待办已经不在库里了',
    errorCode: 'TODO_NOT_FOUND',
  });
  const { container } = renderPanel();

  await ask('删掉和张总确认报价那条');

  expect(await screen.findByText('那条待办已经不在库里了')).toBeVisible();
  expect(container.querySelector('.as-bubble.is-error')).not.toBeNull();
  expect(container.querySelector('.as-tag.is-bad')?.textContent).toBe('失败');
});

it('待确认卡片挂在 as-confirm 上，并带「尚未执行」chip', async () => {
  api.sendAssistantMessage.mockResolvedValue({
    kind: 'CONFIRMATION_REQUIRED',
    message: '请确认后我再执行：删除待办：「和张总确认报价」',
    proposal: {
      pendingActionId: PENDING_ID,
      tool: 'todo.delete',
      summary: '删除待办：「和张总确认报价」',
      arguments: { todoId: 'todo-quote' },
    },
  });
  const { container } = renderPanel();

  await ask('删掉和张总确认报价那条');

  expect(await screen.findByText('需要你确认')).toBeVisible();
  expect(container.querySelector('.as-confirm')).not.toBeNull();
  expect(container.querySelector('.as-tag.is-warn')?.textContent).toBe('尚未执行');
});
