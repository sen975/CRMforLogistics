import { beforeEach, describe, expect, it } from 'vitest';
import {
  createTodo,
  getTodosForDate,
  loadTodos,
  removeTodo,
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
});
