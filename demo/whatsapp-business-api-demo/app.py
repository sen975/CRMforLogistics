from __future__ import annotations

import argparse
import json
import mimetypes
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from wa_demo.client import WhatsAppCloudClient
from wa_demo.config import AppConfig, config_from_dict
from wa_demo.store import MessageStore
from wa_demo.webhook import extract_status_updates, extract_webhook_messages, verify_challenge


class DemoRuntime:
    def __init__(self, root_dir: str | Path | None = None):
        self.root_dir = Path(root_dir or Path(__file__).parent)
        self.data_dir = self.root_dir / "data"
        self.config_path = self.root_dir / "config.json"
        self.store = MessageStore(self.data_dir / "whatsapp_business_demo.db")
        self.config = self._load_config()

    def _load_config(self) -> AppConfig:
        if not self.config_path.exists():
            return AppConfig()
        with self.config_path.open("r", encoding="utf-8-sig") as f:
            return config_from_dict(json.load(f))

    def save_config(self, data: dict[str, Any]) -> dict[str, Any]:
        merged = {
            "mode": data.get("mode", self.config.mode),
            "graph_version": data.get("graph_version", self.config.graph_version),
            "phone_number_id": data.get("phone_number_id", self.config.phone_number_id),
            "business_account_id": data.get("business_account_id", self.config.business_account_id),
            "access_token": data.get("access_token", self.config.access_token),
            "verify_token": data.get("verify_token", self.config.verify_token),
        }
        self.config = config_from_dict(merged)
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        with self.config_path.open("w", encoding="utf-8") as f:
            json.dump(merged, f, ensure_ascii=False, indent=2)
        return self.config.to_public_dict()

    def status(self) -> dict[str, Any]:
        return {"ok": True, "config": self.config.to_public_dict()}

    def send_text(self, data: dict[str, Any]) -> dict[str, Any]:
        to = self._require(data, "to")
        body = self._require(data, "body")
        client = WhatsAppCloudClient(self.config)
        payload = client.build_text_payload(to=to, body=body, preview_url=bool(data.get("preview_url", False)))
        response = client.send_payload(payload)
        contact_id = self.store.upsert_contact(wa_id=payload["to"], phone=payload["to"], name=str(data.get("name", "")))
        self.store.record_message(
            wa_message_id=self._response_message_id(response),
            contact_id=contact_id,
            direction="outgoing",
            message_type="text",
            content=body,
            payload={"request": payload, "response": response},
        )
        return {"request": payload, "response": response}

    def send_template(self, data: dict[str, Any]) -> dict[str, Any]:
        to = self._require(data, "to")
        template_name = self._require(data, "template_name")
        language = str(data.get("language") or "en_US")
        body_parameters = data.get("body_parameters") or []
        if isinstance(body_parameters, str):
            body_parameters = [item.strip() for item in body_parameters.split(",") if item.strip()]
        client = WhatsAppCloudClient(self.config)
        payload = client.build_template_payload(
            to=to,
            template_name=template_name,
            language=language,
            body_parameters=list(body_parameters),
        )
        response = client.send_payload(payload)
        contact_id = self.store.upsert_contact(wa_id=payload["to"], phone=payload["to"], name=str(data.get("name", "")))
        content = f"template:{template_name} {language} {' '.join(map(str, body_parameters))}".strip()
        self.store.record_message(
            wa_message_id=self._response_message_id(response),
            contact_id=contact_id,
            direction="outgoing",
            message_type="template",
            content=content,
            payload={"request": payload, "response": response},
        )
        return {"request": payload, "response": response}

    def send_media(self, data: dict[str, Any]) -> dict[str, Any]:
        to = self._require(data, "to")
        media_type = self._require(data, "media_type")
        link = str(data.get("link") or "").strip() or None
        media_id = str(data.get("media_id") or "").strip() or None
        caption = str(data.get("caption") or "")
        client = WhatsAppCloudClient(self.config)
        payload = client.build_media_payload(to=to, media_type=media_type, link=link, media_id=media_id, caption=caption)
        response = client.send_payload(payload)
        contact_id = self.store.upsert_contact(wa_id=payload["to"], phone=payload["to"], name=str(data.get("name", "")))
        content = f"{media_type}:{link or media_id} {caption}".strip()
        self.store.record_message(
            wa_message_id=self._response_message_id(response),
            contact_id=contact_id,
            direction="outgoing",
            message_type=media_type,
            content=content,
            payload={"request": payload, "response": response},
        )
        return {"request": payload, "response": response}

    def ingest_webhook(self, payload: dict[str, Any]) -> dict[str, Any]:
        event_id = self.store.record_webhook_event(payload)
        messages = extract_webhook_messages(payload)
        statuses = extract_status_updates(payload)
        for message in messages:
            contact_id = self.store.upsert_contact(
                wa_id=message["wa_id"],
                phone=message["phone"],
                name=message["profile_name"],
            )
            self.store.record_message(
                wa_message_id=message["wa_message_id"],
                contact_id=contact_id,
                direction="incoming",
                message_type=message["message_type"],
                content=message["content"],
                payload=message["payload"],
            )
        for status in statuses:
            self.store.record_status(
                wa_message_id=status["wa_message_id"],
                recipient_id=status["recipient_id"],
                status=status["status"],
                payload=status["payload"],
            )
        return {"ok": True, "event_id": event_id, "messages": len(messages), "statuses": len(statuses)}

    def verify_webhook(self, query: dict[str, list[str]]) -> str | None:
        return verify_challenge(query, self.config.verify_token)

    def list_messages(self, query: str = "") -> dict[str, Any]:
        return {"items": self.store.search_messages(query=query)}

    def list_events(self) -> dict[str, Any]:
        return {"items": self.store.list_webhook_events()}

    def list_statuses(self) -> dict[str, Any]:
        return {"items": self.store.list_statuses()}

    @staticmethod
    def _require(data: dict[str, Any], key: str) -> str:
        value = data.get(key)
        if value is None or str(value).strip() == "":
            raise ValueError(f"{key} is required")
        return str(value).strip()

    @staticmethod
    def _response_message_id(response: dict[str, Any]) -> str:
        if response.get("dry_run"):
            return str(response.get("local_message_id"))
        messages = response.get("messages") or []
        if messages:
            return str(messages[0].get("id", ""))
        return "unknown"


class DemoRequestHandler(BaseHTTPRequestHandler):
    runtime: DemoRuntime
    static_dir: Path

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/status":
            self._json(self.runtime.status())
            return
        if parsed.path == "/api/messages":
            query = parse_qs(parsed.query).get("q", [""])[0]
            self._json(self.runtime.list_messages(query=query))
            return
        if parsed.path == "/api/events":
            self._json(self.runtime.list_events())
            return
        if parsed.path == "/api/statuses":
            self._json(self.runtime.list_statuses())
            return
        if parsed.path == "/webhook/whatsapp":
            challenge = self.runtime.verify_webhook(parse_qs(parsed.query, keep_blank_values=True))
            if challenge is None:
                self._text("Forbidden", HTTPStatus.FORBIDDEN)
            else:
                self._text(challenge)
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
            elif parsed.path == "/api/send-media":
                self._json(self.runtime.send_media(data))
            elif parsed.path == "/webhook/whatsapp":
                self._json(self.runtime.ingest_webhook(data))
            else:
                self._json({"error": "not found"}, HTTPStatus.NOT_FOUND)
        except Exception as exc:
            self._json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length == 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def _json(self, data: dict[str, Any], status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(data, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _text(self, data: str, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = data.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
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
    parser = argparse.ArgumentParser(description="WhatsApp Business Cloud API direct demo")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default=8072, type=int)
    args = parser.parse_args()
    server = make_server(args.host, args.port)
    print(f"WhatsApp Business API demo running at http://{args.host}:{args.port}")
    print("Webhook endpoint: /webhook/whatsapp")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("stopping")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()