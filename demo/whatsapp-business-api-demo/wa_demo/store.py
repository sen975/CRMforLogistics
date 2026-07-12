from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Any


class MessageStore:
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
                    wa_id TEXT NOT NULL UNIQUE,
                    phone TEXT NOT NULL DEFAULT '',
                    name TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );

                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    wa_message_id TEXT NOT NULL,
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
                    payload_json TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );

                CREATE TABLE IF NOT EXISTS message_statuses (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    wa_message_id TEXT NOT NULL,
                    recipient_id TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT '',
                    payload_json TEXT NOT NULL DEFAULT '{}',
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                """
            )

    def upsert_contact(self, wa_id: str, phone: str = "", name: str = "") -> int:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO contacts (wa_id, phone, name)
                VALUES (?, ?, ?)
                ON CONFLICT(wa_id) DO UPDATE SET
                    phone = excluded.phone,
                    name = excluded.name,
                    updated_at = CURRENT_TIMESTAMP
                """,
                (wa_id, phone, name),
            )
            row = conn.execute("SELECT id FROM contacts WHERE wa_id = ?", (wa_id,)).fetchone()
            return int(row["id"])

    def record_message(
        self,
        wa_message_id: str,
        contact_id: int | None,
        direction: str,
        message_type: str,
        content: str,
        payload: dict[str, Any] | None = None,
    ) -> int:
        with self._connect() as conn:
            cursor = conn.execute(
                """
                INSERT INTO messages (wa_message_id, contact_id, direction, message_type, content, payload_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    wa_message_id,
                    contact_id,
                    direction,
                    message_type,
                    content,
                    json.dumps(payload or {}, ensure_ascii=False),
                ),
            )
            return int(cursor.lastrowid)

    def record_webhook_event(self, payload: dict[str, Any]) -> int:
        with self._connect() as conn:
            cursor = conn.execute(
                "INSERT INTO webhook_events (payload_json) VALUES (?)",
                (json.dumps(payload, ensure_ascii=False),),
            )
            return int(cursor.lastrowid)

    def record_status(self, wa_message_id: str, recipient_id: str, status: str, payload: dict[str, Any]) -> int:
        with self._connect() as conn:
            cursor = conn.execute(
                """
                INSERT INTO message_statuses (wa_message_id, recipient_id, status, payload_json)
                VALUES (?, ?, ?, ?)
                """,
                (wa_message_id, recipient_id, status, json.dumps(payload, ensure_ascii=False)),
            )
            return int(cursor.lastrowid)

    def search_messages(self, query: str = "", limit: int = 50) -> list[dict[str, Any]]:
        like = f"%{query}%"
        where = "WHERE m.content LIKE ? OR c.name LIKE ? OR c.wa_id LIKE ?" if query else ""
        params: tuple[Any, ...] = (like, like, like, limit) if query else (limit,)
        with self._connect() as conn:
            rows = conn.execute(
                f"""
                SELECT
                    m.id,
                    m.wa_message_id,
                    m.direction,
                    m.message_type,
                    m.content,
                    m.payload_json,
                    m.created_at,
                    c.wa_id,
                    c.phone,
                    c.name AS contact_name
                FROM messages m
                LEFT JOIN contacts c ON c.id = m.contact_id
                {where}
                ORDER BY m.id DESC
                LIMIT ?
                """,
                params,
            ).fetchall()
            return [dict(row) for row in rows]

    def list_webhook_events(self, limit: int = 20) -> list[dict[str, Any]]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT id, payload_json, created_at
                FROM webhook_events
                ORDER BY id DESC
                LIMIT ?
                """,
                (limit,),
            ).fetchall()
            return [dict(row) for row in rows]

    def list_statuses(self, limit: int = 20) -> list[dict[str, Any]]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT id, wa_message_id, recipient_id, status, payload_json, created_at
                FROM message_statuses
                ORDER BY id DESC
                LIMIT ?
                """,
                (limit,),
            ).fetchall()
            return [dict(row) for row in rows]