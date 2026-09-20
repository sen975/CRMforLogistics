CREATE TABLE todo_items (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    due_date date NOT NULL,
    title varchar(200) NOT NULL,
    due_time time,
    note varchar(1000),
    completed boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_todo_items_title CHECK (length(trim(title)) > 0)
);

CREATE INDEX ix_todo_items_user_date ON todo_items (user_id, due_date, due_time NULLS LAST, created_at);
