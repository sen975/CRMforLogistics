-- 助手「待确认动作」表。
--
-- 动机：模型给出一个 `todo.complete` / `todo.delete` / `todo.update` 的调用意图时，本系统不直接执行，
-- 而是先落一行 PENDING，把「执行授权」的交回动作挂在一次显式的人工确认上。这张表就是那份授权的载体。
--
-- 为什么不把待确认动作交给前端持有：这个对象携带的是**执行授权**——前端持有的版本可被篡改
-- （改掉 arguments.todoId 就能让用户点的那次确认落到另一条待办上），而且「取消 / 过期 / 已确认」
-- 这些状态一旦放在客户端就没有可信的单一真源。落库后确认、取消、过期三件事都有据可查。
--
-- 过期采用**惰性判定**：不建定时清理任务，确认/取消时比对 expires_at 并就地置 EXPIRED。
-- 理由与 wecom_contact_events 一致——本期先不引入第二套后台清理机制；行本身很小，
-- 且「过期」这一状态对排障有价值（能回答「用户当时是没点，还是点了但已经太晚」）。
CREATE TABLE assistant_pending_actions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- 前端生成的会话号，用于把同一轮对话的审计串起来；可空（前端还没实现时会话号缺失不应阻塞落库）。
    conversation_id uuid,
    tool_name varchar(64) NOT NULL,
    -- 模型给出的调用参数原文。确认时要拿它**重新校验**一遍，因此必须原样保存，
    -- 不能在写入时就把它们规范化/丢弃——那样确认就变成了「带着陈旧假设执行」。
    arguments jsonb NOT NULL,
    -- 确认卡片上展示的「将要做的事」，必须含标题与日期（歧义消解的唯一手段，见设计文档 §3.2）。
    summary varchar(500) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- 确认/取消/过期发生的时刻。PENDING 时为 NULL。
    decided_at timestamptz,
    CONSTRAINT ck_assistant_pending_actions_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED'))
);

-- 唯一实际查询形状：确认/取消时按 (id, user_id) 取行，以及按用户列未决动作。
CREATE INDEX ix_assistant_pending_actions_user_status
    ON assistant_pending_actions (user_id, status, expires_at DESC);

COMMENT ON TABLE assistant_pending_actions IS
    'Assistant actions awaiting explicit user confirmation; the row carries the execution authorization';
COMMENT ON COLUMN assistant_pending_actions.arguments IS
    'Raw model-proposed arguments, re-validated at confirm time; never normalised on write';
COMMENT ON COLUMN assistant_pending_actions.status IS
    'PENDING / CONFIRMED / CANCELLED / EXPIRED; EXPIRE is decided lazily on confirm or cancel, not by a sweeper';
