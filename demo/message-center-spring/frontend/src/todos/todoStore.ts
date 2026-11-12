export type TodoItem = {
  id: string;
  date: string;
  title: string;
  time?: string;
  note?: string;
  completed: boolean;
  createdAt: string;
};

const TODO_KEY = 'message-center:todo-calendar:v1';

function id() {
  return typeof crypto?.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function read<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) as T : fallback;
  } catch { return fallback; }
}

export function loadTodos(): TodoItem[] {
  const value = read<unknown>(TODO_KEY, []);
  return Array.isArray(value) ? value.filter((item): item is TodoItem => !!item && typeof item === 'object' && typeof (item as TodoItem).id === 'string' && typeof (item as TodoItem).date === 'string' && typeof (item as TodoItem).title === 'string') : [];
}

export function saveTodos(todos: TodoItem[]) { localStorage.setItem(TODO_KEY, JSON.stringify(todos)); }

export function getTodosForDate<T extends { date: string; time?: string | null }>(todos: T[], date: string) { return todos.filter((todo) => todo.date === date).sort((a, b) => (a.time ?? '99:99').localeCompare(b.time ?? '99:99')); }

export function createTodo(input: Pick<TodoItem, 'date' | 'title'> & Partial<Pick<TodoItem, 'time' | 'note'>>): TodoItem {
  const todo: TodoItem = { id: id(), date: input.date, title: input.title.trim(), time: input.time || undefined, note: input.note?.trim() || undefined, completed: false, createdAt: new Date().toISOString() };
  saveTodos([...loadTodos(), todo]);
  return todo;
}

export function toggleTodo(todoId: string, completed: boolean) { const next = loadTodos().map((todo) => todo.id === todoId ? { ...todo, completed } : todo); saveTodos(next); return next; }
export function removeTodo(todoId: string) { const next = loadTodos().filter((todo) => todo.id !== todoId); saveTodos(next); return next; }

export { TODO_KEY };
