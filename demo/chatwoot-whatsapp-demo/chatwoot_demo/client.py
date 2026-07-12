from __future__ import annotations

import json
from typing import Any, Callable
from urllib import request as urllib_request

from .config import AppConfig


class MockChatwootClient:
    def __init__(self) -> None:
        self._contact_id = 1000
        self._conversation_id = 9000
        self._message_id = 5000

    def create_contact(self, phone: str, name: str = "") -> dict[str, Any]:
        self._contact_id += 1
        return {
            "id": self._contact_id,
            "name": name,
            "phone_number": phone,
            "source_id": f"whatsapp:{phone}",
        }

    def create_conversation(self, contact_id: int | str, source_id: str) -> dict[str, Any]:
        self._conversation_id += 1
        return {
            "id": self._conversation_id,
            "contact_id": contact_id,
            "source_id": source_id,
            "status": "open",
        }

    def send_text(self, conversation_id: int | str, content: str) -> dict[str, Any]:
        self._message_id += 1
        return {
            "id": self._message_id,
            "conversation_id": conversation_id,
            "message_type": "outgoing",
            "content": content,
            "private": False,
        }

    def send_template(
        self,
        conversation_id: int | str,
        template_name: str,
        language: str,
        parameters: list[str] | None = None,
    ) -> dict[str, Any]:
        self._message_id += 1
        return {
            "id": self._message_id,
            "conversation_id": conversation_id,
            "message_type": "outgoing",
            "content": "",
            "private": False,
            "template_params": {
                "name": template_name,
                "language": language,
                "parameters": parameters or [],
            },
        }


class ChatwootApiClient:
    def __init__(
        self,
        config: AppConfig,
        opener: Callable[[urllib_request.Request, int], Any] | None = None,
        timeout: int = 20,
    ):
        config.require_real_fields()
        self.config = config
        self.opener = opener or urllib_request.urlopen
        self.timeout = timeout

    def create_contact(self, phone: str, name: str = "") -> dict[str, Any]:
        return self._post(
            f"/api/v1/accounts/{self.config.account_id}/contacts",
            {"name": name, "phone_number": phone, "inbox_id": self.config.inbox_id},
        )

    def create_conversation(self, contact_id: int | str, source_id: str) -> dict[str, Any]:
        return self._post(
            f"/api/v1/accounts/{self.config.account_id}/conversations",
            {
                "source_id": source_id,
                "inbox_id": self.config.inbox_id,
                "contact_id": contact_id,
                "status": "open",
            },
        )

    def send_text(self, conversation_id: int | str, content: str) -> dict[str, Any]:
        return self._post(
            f"/api/v1/accounts/{self.config.account_id}/conversations/{conversation_id}/messages",
            {"content": content, "message_type": "outgoing", "private": False},
        )

    def send_template(
        self,
        conversation_id: int | str,
        template_name: str,
        language: str,
        parameters: list[str] | None = None,
    ) -> dict[str, Any]:
        return self._post(
            f"/api/v1/accounts/{self.config.account_id}/conversations/{conversation_id}/messages",
            {
                "message_type": "outgoing",
                "private": False,
                "template_params": {
                    "name": template_name,
                    "language": language,
                    "parameters": parameters or [],
                },
            },
        )

    def _post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib_request.Request(
            self.config.normalized_url + path,
            data=data,
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "Api-Access-Token": self.config.api_token,
            },
            method="POST",
        )
        with self.opener(req, timeout=self.timeout) as response:
            raw = response.read().decode("utf-8")
        return json.loads(raw) if raw else {}
