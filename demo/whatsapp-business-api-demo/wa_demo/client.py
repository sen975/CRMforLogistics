from __future__ import annotations

import json
import time
from typing import Any, Callable
from urllib import request as urllib_request

from .config import AppConfig


class WhatsAppCloudClient:
    def __init__(
        self,
        config: AppConfig,
        opener: Callable[[urllib_request.Request, int], Any] | None = None,
        timeout: int = 20,
    ):
        self.config = config
        self.opener = opener or urllib_request.urlopen
        self.timeout = timeout

    def build_text_payload(self, to: str, body: str, preview_url: bool = False) -> dict[str, Any]:
        return {
            "messaging_product": "whatsapp",
            "recipient_type": "individual",
            "to": self._normalize_to(to),
            "type": "text",
            "text": {"preview_url": preview_url, "body": body},
        }

    def build_template_payload(
        self,
        to: str,
        template_name: str,
        language: str,
        body_parameters: list[str] | None = None,
    ) -> dict[str, Any]:
        template: dict[str, Any] = {
            "name": template_name,
            "language": {"code": language},
        }
        if body_parameters:
            template["components"] = [
                {
                    "type": "body",
                    "parameters": [{"type": "text", "text": str(item)} for item in body_parameters],
                }
            ]
        return {
            "messaging_product": "whatsapp",
            "recipient_type": "individual",
            "to": self._normalize_to(to),
            "type": "template",
            "template": template,
        }

    def build_media_payload(
        self,
        to: str,
        media_type: str,
        link: str | None = None,
        media_id: str | None = None,
        caption: str = "",
    ) -> dict[str, Any]:
        if media_type not in {"image", "audio", "video", "document", "sticker"}:
            raise ValueError("media_type must be image, audio, video, document, or sticker")
        if not link and not media_id:
            raise ValueError("link or media_id is required")
        media: dict[str, Any] = {"link": link} if link else {"id": media_id}
        if caption and media_type in {"image", "video", "document"}:
            media["caption"] = caption
        return {
            "messaging_product": "whatsapp",
            "recipient_type": "individual",
            "to": self._normalize_to(to),
            "type": media_type,
            media_type: media,
        }

    def send_payload(self, payload: dict[str, Any]) -> dict[str, Any]:
        if self.config.mode == "dry_run":
            return {
                "dry_run": True,
                "local_message_id": f"dryrun.{int(time.time() * 1000)}",
                "request": {
                    "url": self._messages_url(),
                    "method": "POST",
                    "json": payload,
                },
            }
        self.config.require_real_fields()
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib_request.Request(
            self._messages_url(),
            data=data,
            method="POST",
            headers={
                "Authorization": f"Bearer {self.config.access_token}",
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )
        with self.opener(req, timeout=self.timeout) as response:
            raw = response.read().decode("utf-8")
        return json.loads(raw) if raw else {}

    def send_text(self, to: str, body: str, preview_url: bool = False) -> dict[str, Any]:
        return self.send_payload(self.build_text_payload(to=to, body=body, preview_url=preview_url))

    def send_template(self, to: str, template_name: str, language: str, body_parameters: list[str] | None = None) -> dict[str, Any]:
        return self.send_payload(
            self.build_template_payload(
                to=to,
                template_name=template_name,
                language=language,
                body_parameters=body_parameters,
            )
        )

    def send_media(
        self,
        to: str,
        media_type: str,
        link: str | None = None,
        media_id: str | None = None,
        caption: str = "",
    ) -> dict[str, Any]:
        return self.send_payload(
            self.build_media_payload(
                to=to,
                media_type=media_type,
                link=link,
                media_id=media_id,
                caption=caption,
            )
        )

    def _messages_url(self) -> str:
        return f"{self.config.graph_base_url}/{self.config.phone_number_id}/messages"

    @staticmethod
    def _normalize_to(to: str) -> str:
        return to.strip().replace("+", "").replace(" ", "")