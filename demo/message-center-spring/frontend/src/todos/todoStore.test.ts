import { beforeEach, describe, expect, it } from 'vitest';
import {
  createTodo,
  getTodosForDate,
  loadReminderRecords,
  loadTodos,
  removeTodo,
  saveReminderRecord,
  toggleTodo,
} from './todoStore';

describe('todoStore', () => {
  beforeEach(() => window.localStorage.clear());

  it('creates and filters todos by local date', () => {
    const first = createTodo({ date: '2026-09-16', title: '确认仓库' });
    createTodo({ date: '2026-09-17', title: '发送照片' });

    expect(getTodosForDate(loadTodos(), '2026-09-16')).toHaveLength(1);
    expect(getTodosForDate(loadTodos(), '2026-09-17')[0]?.title).toBe('发送照片');
  });

  it('toggles and removes a todo', () => {
    const todo = createTodo({ date: '2026-09-16', title: '确认仓库' });
    expect(toggleTodo(todo.id, true)[0]?.completed).toBe(true);
    expect(removeTodo(todo.id)).toHaveLength(0);
  });

  it('recovers from malformed persisted data', () => {
    window.localStorage.setItem('message-center:todo-calendar:v1', '{bad');
    expect(loadTodos()).toEqual([]);
  });

  it('keeps only the latest ten reminder records', () => {
    for (let index = 0; index < 12; index += 1) {
      saveReminderRecord({ id: String(index), date: '2026-09-16', taskCount: index, sentAt: new Date().toISOString(), status: 'sent' });
    }
    expect(loadReminderRecords()).toHaveLength(10);
    expect(loadReminderRecords()[0]?.taskCount).toBe(11);
  });
});
