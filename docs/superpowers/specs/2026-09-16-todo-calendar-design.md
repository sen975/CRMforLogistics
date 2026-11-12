# 待办日历与企业微信提醒设计

## 目标

在现有消息中心中增加一个可持续使用的“待办日历”工作台：用户通过月历选择日期，查看并手动维护当天待办；企业微信提醒**由服务端自动调度**（当天汇总 + 事项开始前 N 小时），前端不再提供手动触发入口。前端只调用本项目后端，后端通过现有企业微信安装凭据和官方消息发送接口完成推送。

## 范围与非目标

- 范围：独立 `/todo-calendar` 路由、月历日期选择、待办新增/完成/删除、当天待办汇总、官方 API 自动提醒、服务端按用户持久化、响应式布局。
- 非目标：多人协作、重复任务、待办服务端同步。

## 提醒规则（2026-09-20 更新：手动触发 → 服务端自动调度）

原设计把提醒做成页面上的“发送今日提醒”按钮（`POST /api/wecom/send-todo-reminder`），必须人工点击。现改为服务端调度，该端点与前端按钮、本地发送记录一并删除：

- **当天汇总**：当天 `app.todo-reminder-daily-hour`（默认 0 点）起，当天还没成功发过的用户各一条，内容是其当天全部未完成待办。判据是“当天已跨过钟点且当天未发送”，而非“此刻正好是钟点”，因此服务在钟点前后重启、或用户钟点后才补建当天待办都不会漏，一天仍最多一条。
- **事项开始前 N 小时**：仅带具体时间的待办参与，进入 `(now, now + leadHours]`（默认提前 3 小时）窗口时逐条发送。窗口上界“还没开始”不可省：停机恢复后不应把早已开始的事项补成“即将开始”。
- 只发给已绑定企业微信的账号；时区取 `app.todo-reminder-zone`（默认 `Asia/Shanghai`）—— `due_date`/`due_time` 是本地日历时间语义，而 `Clock` bean 是 UTC。
- 幂等：当天汇总记在 `todo_daily_reminders`（`user_id + reminder_date` 唯一），提前提醒记在 `todo_items.lead_reminder_sent_at`。
- 实现：`service/wecom/WeComTodoReminderScheduler`，以 60 秒节拍注册进 `AdaptivePollingScheduler`（`baseline = ceiling = 60s`，跑在独立调度链上），详见 `docs/项目结构与数据流转表.md` 流转 C。
- 页面不承载任何提醒 UI：发送按钮与只读说明块均已移除（2026-09-21）。提醒规则只存在于服务端配置（`app.todo-reminder-*`），页面仅负责待办本身的增删改查。

## 页面结构

```
┌─────────────────────────────────────────────────────────┐
│ 待办日历       2026年9月        ← →   今天   新建待办   │
│ 一页纸安排今天的运输、客户和跟进                         │
├───────────────────────┬─────────────────────────────────┤
│ 月历（点击日期）       │ 9月16日 · 星期三                 │
│  日 一 二 三 四 五 六   │ 今日 3 项 · 已完成 1             │
│  …                     │ [ ] 确认青岛仓出库时间   09:30   │
│                       │ [✓] 回访客户李宁           13:00   │
│                       │ [ ] 发送装车照片             17:00   │
│                       │                                 │
│                       │         （整页撑满视口，无滚动）  │
└───────────────────────┴─────────────────────────────────┘
```

## 布局（2026-09-21 更新：整页贴合视口，不出现滚动）

原实现用固定像素高度（`calendar-day` 最小 76px、`todo-page` 用 `min-height:100%`），在 1512×890 这类窗口下合计约 875px，超出内容区约 794px 的可视高度，导致必须滚动才能看到月历最后一行。现改为**弹性贴合**：

- `.todo-page` 由 `min-height:100%` 改为 `height:100%` + `display:flex`，`.todo-paper` 成为 `flex:1` 的纵向 flex 容器 —— 外层 `AppLayout` 的 `Content`（`flex:1, overflow:auto`）已有确定高度，百分比高度可正确解析。
- `.calendar-grid` 用 `grid-auto-rows:minmax(0,1fr)` 吃掉剩余高度，`.todo-list` 用 `flex:1 + overflow:auto` 兜住过多待办，其余区块一律 `flex:0 0 auto`。
- 字号与间距改用 `clamp(下限, vh/vw, 上限)`，随视口高度自适应收缩。
- `max-width:800px` 的媒体查询显式恢复 `height:auto` + 可滚动，单列布局下不再强行压缩。

实测（Chrome headless，真实 dev server）：1512×890 / 1440×780 / 1280×700 / 1512×640 / 1200×600 五档下 `content`、`todo-page`、`todo-paper`、`document` 的 `scrollHeight - clientHeight` 全部为 0，月历 6 行完整可见、末格日期不被裁切；窄屏 700×900 回到单列可滚动。

月历显示任务数量小圆点；点击日期只改变共享的 `selectedDate`，右侧详情从任务 owner 读取该日期任务。新增待办表单要求标题，时间和备注可选；保存后立即回到详情列表。完成/删除均为即时服务端操作。

## 数据与 owner

`TodoCalendarPage` 只负责展示和事件编排；待办模型与本地缓存逻辑放在同页面旁的 `todoStore.ts`：

```ts
type TodoItem = {
  id: string;
  date: string;       // YYYY-MM-DD，本地时区
  title: string;
  time?: string;      // HH:mm
  note?: string;
  completed: boolean;
  createdAt: string;
};
```

待办由后端 `todo_items` 表按 `user_id` 隔离保存，前端通过 `/api/todos` CRUD；旧版浏览器缓存仅在首次加载且服务端为空时迁移一次。提醒的发送台账由后端持有（`todo_daily_reminders` + `todo_items.lead_reminder_sent_at`），前端不记录任何发送状态 —— 手动触发时代的 `REMINDER_KEY` 本地记录已随按钮一并删除。

## 验收与测试

- 前端单元测试覆盖日期筛选、任务新增/切换完成/删除、本地存储损坏降级。
- 后端 `WeComTodoReminderSchedulerTest` 覆盖：按业务时区取“今天”、汇总钟点门限、台账抢占后不重复发送、提前提醒窗口边界与文案、发送失败退回标记、超长正文按字节截断、注册节拍为固定 60 秒。
- 页面测试覆盖点击日期切换详情、打开新增表单并保存、勾选完成。
- 运行 `npm run build` 和前端测试脚本；检查桌面与窄屏布局，确认现有路由和导航行为不回归。
