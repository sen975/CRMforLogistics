from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class AppConfig:
    chatwoot_url: str = ""
    account_id: int | None = None
    inbox_id: int | None = None
    api_token: str = ""
    mode: str = "mock"
    webhook_secret: str = ""

    def __post_init__(self) -> None:
        if self.mode not in {"mock", "real"}:
            raise ValueError("mode must be 'mock' or 'real'")

    def to_public_dict(self) -> dict:
        return {
            "chatwoot_url": self.chatwoot_url,
            "account_id": self.account_id,
            "inbox_id": self.inbox_id,
            "api_token": "configured" if self.api_token else "missing",
            "mode": self.mode,
            "webhook_secret": "configured" if self.webhook_secret else "missing",
        }

    def require_real_fields(self) -> None:
        missing = []
        if not self.chatwoot_url:
            missing.append("chatwoot_url")
        if self.account_id is None:
            missing.append("account_id")
        if self.inbox_id is None:
            missing.append("inbox_id")
        if not self.api_token:
            missing.append("api_token")
        if missing:
            raise ValueError("missing real mode config: " + ", ".join(missing))

    @property
    def normalized_url(self) -> str:
        return self.chatwoot_url.rstrip("/")


def config_from_dict(data: dict) -> AppConfig:
    account_id = data.get("account_id")
    inbox_id = data.get("inbox_id")
    return AppConfig(
        chatwoot_url=str(data.get("chatwoot_url", "")),
        account_id=int(account_id) if account_id not in (None, "") else None,
        inbox_id=int(inbox_id) if inbox_id not in (None, "") else None,
        api_token=str(data.get("api_token", "")),
        mode=str(data.get("mode", "mock") or "mock"),
        webhook_secret=str(data.get("webhook_secret", "")),
    )
