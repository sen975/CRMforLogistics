import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Empty, Form, Input, Modal, Space, Tag, TimePicker, message } from 'antd';
import { CheckOutlined, DeleteOutlined, LeftOutlined, PlusOutlined, RightOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { createTodoApi, deleteTodoApi, fetchTodos, updateTodoApi, type TodoApiItem } from '../api/endpoints';
import { getTodosForDate, loadTodos } from '../todos/todoStore';
import { subscribeAssistantEvents } from '../assistant/assistantEvents';
import './TodoCalendarPage.css';

const weekdays = ['日', '一', '二', '三', '四', '五', '六'];
const formatDate = (date: Dayjs) => date.format('YYYY-MM-DD');

export default function TodoCalendarPage() {
  const [cursor, setCursor] = useState(() => dayjs().startOf('month'));
  const [selectedDate, setSelectedDate] = useState(() => formatDate(dayjs()));
  const [todos, setTodos] = useState<TodoApiItem[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  useState(() => { void fetchTodos().then(async (remote) => {
    if (remote.length === 0 && !localStorage.getItem('message-center:todo-calendar:server-migrated:v1')) {
      const local = loadTodos();
      if (local.length) {
        await Promise.all(local.map((todo) => createTodoApi({ date: todo.date, title: todo.title, time: todo.time, note: todo.note })));
        remote = await fetchTodos();
      }
      localStorage.setItem('message-center:todo-calendar:server-migrated:v1', '1');
    }
    setTodos(remote);
  }).catch(() => setError('待办加载失败，请刷新重试')).finally(() => setLoading(false)); });
  const [form] = Form.useForm<{ title: string; time?: Dayjs; note?: string }>();
  const selected = dayjs(selectedDate);
  const selectedTodos = useMemo(() => getTodosForDate(todos, selectedDate), [todos, selectedDate]);
  const calendarDays = useMemo(() => {
    const start = cursor.startOf('month').startOf('week');
    return Array.from({ length: 42 }, (_, index) => start.add(index, 'day'));
  }, [cursor]);
  const today = formatDate(dayjs());

  const refresh = () => { void fetchTodos().then(setTodos).catch(() => setError('待办同步失败，请刷新重试')); };
  // 助手在**别的页面**改了待办时，这一页不会自己知道（它是 useState + 手动 fetch，不走 react-query）。
  // 只订阅一次是安全的：refresh 只用到 setTodos / setError 这两个稳定引用，
  // 因此首个渲染时捕获的那个闭包不会「过期」，不需要每次渲染重新订阅。
  // 刻意不迁到 react-query：那是另一件事，且会把本次改动的回归面放大。
  useEffect(() => subscribeAssistantEvents(() => refresh()), []);
  const addTodo = async (values: { title: string; time?: Dayjs; note?: string }) => {
    await createTodoApi({ date: selectedDate, title: values.title, time: values.time?.format('HH:mm'), note: values.note });
    refresh(); form.resetFields(); setModalOpen(false); message.success('待办已写入这一天');
  };

  return <div className="todo-page">
    <div className="todo-paper">
      <header className="todo-header">
        <div><div className="todo-eyebrow">DAILY FIELD NOTES · 待办手记</div><h1>待办日历</h1><p>一页纸安排运输、客户与跟进，让今天的事情有地方落笔。</p></div>
        <Space wrap><Button icon={<LeftOutlined />} aria-label="上个月" onClick={() => setCursor(cursor.subtract(1, 'month'))} /><span className="month-label">{cursor.format('YYYY年M月')}</span><Button icon={<RightOutlined />} aria-label="下个月" onClick={() => setCursor(cursor.add(1, 'month'))} /><Button onClick={() => { const now = dayjs(); setCursor(now.startOf('month')); setSelectedDate(formatDate(now)); }}>今天</Button><Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>新建待办</Button></Space>
      </header>
      <div className="todo-layout">
        <section className="calendar-sheet" aria-label="月历">
          <div className="calendar-weekdays">{weekdays.map((day) => <span key={day}>{day}</span>)}</div>
          <div className="calendar-grid">{calendarDays.map((date) => { const key = formatDate(date); const count = getTodosForDate(todos, key).length; const isSelected = key === selectedDate; return <button key={key} className={`calendar-day ${date.month() !== cursor.month() ? 'muted' : ''} ${isSelected ? 'selected' : ''} ${key === today ? 'today' : ''}`} onClick={() => setSelectedDate(key)}><span>{date.date()}</span>{count > 0 && <i aria-label={`${count} 项待办`}>{count}</i>}</button>; })}</div>
          <div className="calendar-legend"><span><i className="legend-dot" /> 有待办</span><span><i className="legend-ring" /> 当前选择</span></div>
        </section>
        <section className="detail-sheet" aria-label="当天待办">
          <div className="detail-heading"><div><span className="detail-kicker">SELECTED DAY</span><h2>{selected.format('M月D日')} <small>星期{weekdays[selected.day()]}</small></h2></div><Tag color={selectedTodos.length ? 'volcano' : 'default'}>{selectedTodos.length} 项 · 已完成 {selectedTodos.filter((todo) => todo.completed).length}</Tag></div>
          {error && <Alert type="error" showIcon message="待办同步失败" description={error} closable onClose={() => setError(null)} />}
          <div className="todo-list">{loading ? <p>正在加载待办…</p> : selectedTodos.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="这一天还没有待办，写下一件要记住的事。" /> : selectedTodos.map((todo) => <TodoRow key={todo.id} todo={todo} onToggle={(completed) => { void updateTodoApi(todo.id, completed).then(refresh); }} onRemove={() => { void deleteTodoApi(todo.id).then(refresh); }} />)}</div>
          <Button type="dashed" block icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>在这一天添加待办</Button>
        </section>
      </div>
    </div>
    <Modal title={`写一条 ${selected.format('M月D日')} 待办`} open={modalOpen} okText="保存待办" cancelText="取消" onCancel={() => setModalOpen(false)} onOk={() => form.submit()}><Form form={form} layout="vertical" onFinish={addTodo}><Form.Item name="title" label="要记住什么？" rules={[{ required: true, message: '请写下待办标题' }]}><Input autoFocus placeholder="例如：确认青岛仓出库时间" maxLength={120} /></Form.Item><Form.Item name="time" label="时间（可选）"><TimePicker format="HH:mm" minuteStep={5} /></Form.Item><Form.Item name="note" label="备注（可选）"><Input.TextArea rows={3} placeholder="补充联系人、单号或下一步" maxLength={500} /></Form.Item></Form></Modal>
  </div>;
}

function TodoRow({ todo, onToggle, onRemove }: { todo: TodoApiItem; onToggle: (completed: boolean) => void; onRemove: () => void }) {
  return <div className={`todo-row ${todo.completed ? 'completed' : ''}`}><button className="check-button" aria-label={todo.completed ? `标记${todo.title}未完成` : `标记${todo.title}完成`} onClick={() => onToggle(!todo.completed)}>{todo.completed ? <CheckOutlined /> : null}</button><div className="todo-copy"><strong>{todo.title}</strong>{todo.note && <small>{todo.note}</small>}</div>{todo.time && <time>{todo.time}</time>}<Button type="text" danger aria-label={`删除${todo.title}`} icon={<DeleteOutlined />} onClick={onRemove} /></div>;
}
