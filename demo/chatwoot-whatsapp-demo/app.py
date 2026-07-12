from __future__ import annotations

import argparse
import json
import mimetypes
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from chatwoot_demo.client import ChatwootApiClient, MockChatwootClient
from chatwoot_demo.config import AppConfig, config_from_dict
from chatwoot_demo.store import DemoStore


class DemoRuntime:
    def __init__(self, root_dir: str | Path | None = None):
        self.root_dir = Path(root_dir or Path(__file__).parent)
        self.data_dir = self.root_dir / "data"
        self.config_path = self.root_dir / "config.json"
        self.store = DemoStore(self.data_dir / "chatwoot_whatsapp_demo.db")
        self.config = self._load_config()
        self._mock_client = MockChatwootClient()

    def _load_config(self) -> AppConfig:
        if not self.config_path.exists():
            return AppConfig()
        with self.config_path.open("r", encoding="utf-8-sig") as f:
            return config_from_dict(json.load(f))

    def save_config(self, data: dict[str, Any]) -> dict[str, Any]:
        merged = {
            "mode": data.get("mode", self.config.mode),
            "chatwoot_url": data.get("chatwoot_url", self.config.chatwoot_url),
            "account_id": data.get("account_id", self.config.account_id),
            "inbox_id": data.get("inbox_id", self.config.inbox_id),
            "api_token": data.get("api_token", self.config.api_token),
            "webhook_secret": data.get("webhook_secret", self.config.webhook_secret),
        }
        self.config = config_from_dict(merged)
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        with self.config_path.open("w", encoding="utf-8") as f:
            json.dump(merged, f, ensure_ascii=False, indent=2)
        return self.config.to_public_dict()

    def status(self) -> dict[str, Any]:
        return {"config": self.config.to_public_dict(), "ok": True}

    def _client(self):
        if self.config.mode == "real":
            return ChatwootApiClient(self.config)
        return self._mock_client

    def send_text(self, data: dict[str, Any]) -> dict[str, Any]:
        phone = self._require(data, "phone")
        name = str(data.get("name", ""))
        content = self._require(data, "content")
        client = self._client()
        contact = client.create_contact(phone=phone, name=name)
        contact_id = self.store.upsert_contact(
            phone=phone,
            name=name,
            source_id=str(contact.get("source_id") or f"whatsapp:{phone}"),
            external_id=str(contact.get("id", "")),
        )
        conversation = client.create_conversation(contact_id=contact.get("id"), source_id=phone)
        message = client.send_text(conversation_id=conversation["id"], content=content)
        self.store.record_message(
            conversation_id=conversation["id"],
            contact_id=contact_id,
            direction="outgoing",
            message_type="text",
            content=content,
            payload={"contact": contact, "conversation": conversation, "message": message},
        )
        return {"contact": contact, "conversation": conversation, "message": message}

    def send_template(self, data: dict[str, Any]) -> dict[str, Any]:
        phone = self._require(data, "phone")
        name = str(data.get("name", ""))
        template_name = self._require(data, "template_name")
        language = str(data.get("language", "en_US"))
        parameters = data.get("parameters") or []
        if isinstance(parameters, str):
            parameters = [item.strip() for item in parameters.split(",") if item.strip()]
        client = self._client()
        contact = client.create_contact(phone=phone, name=name)
        contact_id = self.store.upsert_contact(
            phone=phone,
            name=name,
            source_id=str(contact.get("source_id") or f"whatsapp:{phone}"),
            external_id=str(contact.get("id", "")),
        )
        conversation = client.create_conversation(contact_id=contact.get("id"), source_id=phone)
        message = client.send_template(
            conversation_id=conversation["id"],
            template_name=template_name,
            language=language,
            parameters=list(parameters),
        )
        content = f"template:{template_name} {language} {' '.join(map(str, parameters))}".strip()
        self.store.record_message(
            conversation_id=conversation["id"],
            contact_id=contact_id,
            direction="outgoing",
            message_type="template",
            content=content,
            payload={"contact": contact, "conversation": conversation, "message": message},
        )
        return {"contact": contact, "conversation": conversation, "message": message}

    def ingest_webhook(self, payload: dict[str, Any]) -> dict[str, Any]:
        event_name = str(payload.get("event") or payload.get("event_name") or "unknown")
        event_id = self.store.record_webhook(event_name, payload)
        direction = self._webhook_direction(payload)
        content = str(payload.get("content") or payload.get("message", {}).get("content") or "")
        if direction and content:
            sender = payload.get("sender") or payload.get("contact") or {}
            phone = str(sender.get("phone_number") or sender.get("phone") or payload.get("phone") or "unknown")
            name = str(sender.get("name") or "")
            external_id = str(sender.get("id") or "")
            contact_id = self.store.upsert_contact(phone=phone, name=name, source_id=f"whatsapp:{phone}", external_id=external_id)
            conversation = payload.get("conversation") or {}
            conversation_id = str(conversation.get("id") or payload.get("conversation_id") or "unknown")
            self.store.record_message(
                conversation_id=conversation_id,
                contact_id=contact_id,
                direction=direction,
                message_type="text",
                content=content,
                payload=payload,
            )
        return {"ok": True, "event_id": event_id, "event_name": event_name}

    def list_messages(self, query: str = "", limit: int = 50) -> dict[str, Any]:
        return {"items": self.store.search_messages(query=query, limit=limit)}

    def list_webhooks(self, limit: int = 20) -> dict[str, Any]:
        return {"items": self.store.list_webhook_events(limit=limit)}

    @staticmethod
    def _webhook_direction(payload: dict[str, Any]) -> str:
        message_type = str(payload.get("message_type") or payload.get("message", {}).get("message_type") or "")
        if message_type in {"incoming", "outgoing"}:
            return message_type
        return ""

    @staticmethod
    def _require(data: dict[str, Any], key: str) -> str:
        value = data.get(key)
        if value is None or str(value).strip() == "":
            raise ValueError(f"{key} is required")
        return str(value).strip()


class DemoRequestHandler(BaseHTTPRequestHandler):
    runtime: DemoRuntime
    static_dir: Path

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/status":
            self._json(self.runtime.status())
            return
        if parsed.path == "/api/messages":
            qs = parse_qs(parsed.query)
            query = qs.get("q", [""])[0]
            self._json(self.runtime.list_messages(query=query))
            return
        if parsed.path == "/api/webhooks":
            self._json(self.runtime.list_webhooks())
            return
        self._static(parsed.path)

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        try:
            data = self._read_json()
            if parsed.path == "/api/config":
                self._json({"config": self.runtime.save_config(data)})
            elif parsed.path == "/api/send-text":
                self._json(self.runtime.send_text(data))
            elif parsed.path == "/api/send-template":
                self._json(self.runtime.send_template(data))
            elif parsed.path == "/webhooks/chatwoot":
                self._json(self.runtime.ingest_webhook(data))
            else:
                self._json({"error": "not found"}, HTTPStatus.NOT_FOUND)
        except Exception as exc:
            self._json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length == 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8")
        return json.loads(raw)

    def _json(self, data: dict[str, Any], status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(data, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _static(self, path: str) -> None:
        if path in {"", "/"}:
            path = "/index.html"
        target = (self.static_dir / path.lstrip("/")).resolve()
        if not str(target).startswith(str(self.static_dir.resolve())) or not target.exists() or not target.is_file():
            self._json({"error": "not found"}, HTTPStatus.NOT_FOUND)
            return
        body = target.read_bytes()
        content_type = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
        if content_type.startswith("text/") or content_type == "application/javascript":
            content_type += "; charset=utf-8"
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: Any) -> None:
        print(f"{self.address_string()} - {format % args}")


def make_server(host: str, port: int, runtime: DemoRuntime | None = None) -> ThreadingHTTPServer:
    runtime = runtime or DemoRuntime()
    static_dir = runtime.root_dir / "static"

    class Handler(DemoRequestHandler):
        pass

    Handler.runtime = runtime
    Handler.static_dir = static_dir
    return ThreadingHTTPServer((host, port), Handler)


def main() -> None:
    parser = argparse.ArgumentParser(description="Chatwoot WhatsApp demo")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default=8071, type=int)
    args = parser.parse_args()
    server = make_server(args.host, args.port)
    print(f"Chatwoot WhatsApp demo running at http://{args.host}:{args.port}")
    print("Webhook endpoint: /webhooks/chatwoot")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("stopping")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()