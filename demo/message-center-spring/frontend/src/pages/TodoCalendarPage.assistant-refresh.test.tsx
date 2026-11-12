import '@testing-library/jest-dom/vitest';
import { App as AntApp } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import dayjs from 'dayjs';
import { beforeEach, expect, it, vi } from 'vitest';
import TodoCalendarPage from './TodoCalendarPage';
import { publishAssistantEvent } from '../assistant/assistantEvents';

const api = vi.hoisted(() => ({
  fetchTodos: vi.fn(),
  createTodoApi: vi.fn(),
  updateTodoApi: vi.fn(),
  deleteTodoApi: vi.fn(),
}));

vi.mock('../api/endpoints', () => api);

const today = dayjs().format('YYYY-MM-DD');

beforeEach(() => {
  vi.clearAllMocks();
});

/**
 * 助手在别的页面标记完成了一条待办，这一页不会自己知道 —— 它是 `useState` + 手动 `fetchTodos()`，
 * 不走 react-query，也没有全局 store。所以助手执行成功后广播一个事件，这一页收到后重读。
 *
 * 断言分两层：先是「又拉了一次」，再是「新数据真的出现在页面上」。
 * 只断言前者的话，把 refresh 接到一个什么都不做的地方也能过。
 */
it('reloads the calendar when the assistant reports an executed action', async () => {
  api.fetchTodos
    .mockResolvedValueOnce([])
    .mockResolvedValue([
      {
        id: 'todo-quote',
        date: today,
        title: '和张总确认报价',
        time: '15:00',
        completed: true,
        createdAt: `${today}T00:00:00Z`,
      },
    ]);

  render(<AntApp><TodoCalendarPage /></AntApp>);

  // 首次加载：这一天还没有待办。
  await waitFor(() => expect(api.fetchTodos).toHaveBeenCalledTimes(1));
  expect(screen.queryByText('和张总确认报价')).not.toBeInTheDocument();

  publishAssistantEvent({ type: 'assistant-action-executed' });

  await waitFor(() => expect(api.fetchTodos).toHaveBeenCalledTimes(2));
  // 用户没有手动刷新，页面上就应该看得见助手刚做完的那件事。
  expect(await screen.findByText('和张总确认报价')).toBeVisible();
});
