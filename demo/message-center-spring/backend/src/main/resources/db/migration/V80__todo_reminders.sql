-- 待办提醒从「前端手动点按钮」升级为「服务端自动调度」：
--   1) 当天汇总：当天 0 点起，当天还没成功发送过汇总、且已绑定企业微信的用户各发一条；
--   2) 提前提醒：待办开始前 N 小时（默认 3）逐条发送，只有带具体时间的待办参与。
-- 两处幂等分开落：
--   * 当天汇总按 (user_id, reminder_date) 唯一，重复 tick 在同一行上抢不到发送权；
--   * 提前提醒按单条待办上的 lead_reminder_sent_at 标记。

ALTER TABLE todo_items
    ADD COLUMN lead_reminder_sent_at timestamptz;

-- 两个扫描入口都只关心「未完成」的待办：当天汇总按 due_date 取，提前提醒再叠加 due_time
-- 与「尚未提醒」。一个部分索引同时服务两者；已有 ix_todo_items_user_date 前导列是 user_id，
-- 按 due_date 单独过滤用不上它。
CREATE INDEX ix_todo_items_pending_due
    ON todo_items (due_date, due_time)
    WHERE completed = false;

CREATE TABLE todo_daily_reminders (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reminder_date date NOT NULL,
    status varchar(16) NOT NULL,
    task_count integer NOT NULL DEFAULT 0,
    attempt_count integer NOT NULL DEFAULT 0,
    message_id varchar(128),
    last_error varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_todo_daily_reminders PRIMARY KEY (user_id, reminder_date),
    CONSTRAINT ck_todo_daily_reminders_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'ABANDONED')),
    CONSTRAINT ck_todo_daily_reminders_counts
        CHECK (task_count >= 0 AND attempt_count >= 0)
);
