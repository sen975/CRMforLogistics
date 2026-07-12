from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Any


class DemoStore:
    def __init__(self, db_path: str | Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_db()

    @contextmanager
    def _connect(self):
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _init_db(self) -> None:
        with self._connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS contacts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    phone TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL DEFAULT '',
                    source_id TEXT NOT NULL DEFAULT '',
                    external_id TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );

                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id TEXT NOT NULL,
                    contact_id INTEGER,
                    direction TEXT NOT NULL,
                    message_type TEXT NOT NULL,
                    content TEXT NOT NULL DEFAULT '',
                    payload_json TEXT NOT NULL DEFAULT '{}',
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY(contact_id) REFERENCES contacts(id)
                );

                CREATE TABLE IF NOT EXISTS webhook_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_name TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                """
            )

    def upsert_contact(self, phone: str, name: str = "", source_id: str = "", external_id: str = "") -> int:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO contacts (phone, name, source_id, external_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(phone) DO UPDATE SET
                    name = excluded.name,
                    source_id = excluded.source_id,
                    external_id = excluded.external_id,
                    updated_at = CURRENT_TIMESTAMP
                """,
                (phone, name, source_id, str(external_id)),
            )
            row = conn.execute("SELECT id FROM contacts WHERE phone = ?", (phone,)).fetchone()
            return int(row["id"])

    def record_message(
        self,
        conversation_id: str | int,
        contact_id: int | None,
        direction: str,
        message_type: str,
        content: str,
        payload: dict[str, Any] | None = None,
    ) -> int:
        with self._connect() as conn:
            cursor = conn.execute(
                """
                INSERT INTO messages (conversation_id, contact_id, direction, message_type, content, payload_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    str(conversation_id),
                    contact_id,
                    direction,
                    message_type,
                    content,
                    json.dumps(payload or {}, ensure_ascii=False),
                ),
            )
            return int(cursor.lastrowid)

    def search_messages(self, query: str = "", limit: int = 50) -> list[dict[str, Any]]:
        like = f"%{query}%"
        where = "WHERE m.content LIKE ? OR c.name LIKE ? OR c.phone LIKE ?" if query else ""
        params: tuple[Any, ...] = (like, like, like, limit) if query else (limit,)
        with self._connect() as conn:
            rows = conn.execute(
                f"""
                SELECT
                    m.id,
                    m.conversation_id,
                    m.direction,
                    m.message_type,
                    m.content,
                    m.payload_json,
                    m.created_at,
                    c.name AS contact_name,
                    c.phone AS phone
                FROM messages m
                LEFT JOIN contacts c ON c.id = m.contact_id
                {where}
                ORDER BY m.id DESC
                LIMIT ?
                """,
                params,
            ).fetchall()
            return [dict(row) for row in rows]

    def record_webhook(self, event_name: str, payload: dict[str, Any]) -> int:
        with self._connect() as conn:
            cursor = conn.execute(
                "INSERT INTO webhook_events (event_name, payload_json) VALUES (?, ?)",
                (event_name, json.dumps(payload, ensure_ascii=False)),
            )
            return int(cursor.lastrowid)

    def list_webhook_events(self, limit: int = 20) -> list[dict[str, Any]]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT id, event_name, payload_json, created_at
                FROM webhook_events
                ORDER BY id DESC
                LIMIT ?
                """,
                (limit,),
            ).fetchall()
            return [dict(row) for row in rows]