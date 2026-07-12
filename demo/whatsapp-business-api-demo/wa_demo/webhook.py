from __future__ import annotations

from urllib.parse import parse_qs


def verify_challenge(query: dict[str, list[str]], verify_token: str) -> str | None:
    mode = _first(query, "hub.mode")
    token = _first(query, "hub.verify_token")
    challenge = _first(query, "hub.challenge")
    if mode == "subscribe" and token == verify_token and challenge:
        return challenge
    return None


def extract_webhook_messages(payload: dict) -> list[dict]:
    items: list[dict] = []
    for entry in payload.get("entry", []):
        for change in entry.get("changes", []):
            value = change.get("value", {})
            contacts = {item.get("wa_id"): item for item in value.get("contacts", [])}
            for message in value.get("messages", []):
                wa_id = str(message.get("from", ""))
                contact = contacts.get(wa_id, {})
                message_type = str(message.get("type", "unknown"))
                items.append(
                    {
                        "wa_message_id": str(message.get("id", "")),
                        "wa_id": wa_id,
                        "phone": wa_id,
                        "profile_name": str(contact.get("profile", {}).get("name", "")),
                        "timestamp": str(message.get("timestamp", "")),
                        "message_type": message_type,
                        "content": _content_for_message(message, message_type),
                        "payload": message,
                    }
                )
    return items


def extract_status_updates(payload: dict) -> list[dict]:
    items: list[dict] = []
    for entry in payload.get("entry", []):
        for change in entry.get("changes", []):
            value = change.get("value", {})
            for status in value.get("statuses", []):
                items.append(
                    {
                        "wa_message_id": str(status.get("id", "")),
                        "recipient_id": str(status.get("recipient_id", "")),
                        "status": str(status.get("status", "")),
                        "timestamp": str(status.get("timestamp", "")),
                        "payload": status,
                    }
                )
    return items


def parse_query_string(query_string: str) -> dict[str, list[str]]:
    return parse_qs(query_string, keep_blank_values=True)


def _content_for_message(message: dict, message_type: str) -> str:
    if message_type == "text":
        return str(message.get("text", {}).get("body", ""))
    if message_type in {"image", "audio", "video", "document", "sticker"}:
        media = message.get(message_type, {})
        caption = media.get("caption") or media.get("filename") or media.get("mime_type") or ""
        media_id = media.get("id") or ""
        return f"{message_type}:{media_id} {caption}".strip()
    if message_type == "button":
        return str(message.get("button", {}).get("text", ""))
    if message_type == "interactive":
        interactive = message.get("interactive", {})
        return str(interactive.get("button_reply", {}).get("title") or interactive.get("list_reply", {}).get("title") or "")
    return message_type


def _first(query: dict[str, list[str]], key: str) -> str:
    values = query.get(key) or []
    return values[0] if values else ""