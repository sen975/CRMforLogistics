from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class AppConfig:
    mode: str = "dry_run"
    graph_version: str = "v20.0"
    phone_number_id: str = ""
    business_account_id: str = ""
    access_token: str = ""
    verify_token: str = ""

    def __post_init__(self) -> None:
        if self.mode not in {"dry_run", "real"}:
            raise ValueError("mode must be 'dry_run' or 'real'")

    @property
    def graph_base_url(self) -> str:
        version = self.graph_version.strip().lstrip("/") or "v20.0"
        return f"https://graph.facebook.com/{version}"

    def to_public_dict(self) -> dict:
        return {
            "mode": self.mode,
            "graph_version": self.graph_version,
            "phone_number_id": self.phone_number_id,
            "business_account_id": self.business_account_id,
            "access_token": "configured" if self.access_token else "missing",
            "verify_token": "configured" if self.verify_token else "missing",
        }

    def require_real_fields(self) -> None:
        missing = []
        if not self.phone_number_id:
            missing.append("phone_number_id")
        if not self.access_token:
            missing.append("access_token")
        if missing:
            raise ValueError("missing real mode config: " + ", ".join(missing))


def config_from_dict(data: dict) -> AppConfig:
    return AppConfig(
        mode=str(data.get("mode") or "dry_run"),
        graph_version=str(data.get("graph_version") or "v20.0"),
        phone_number_id=str(data.get("phone_number_id") or ""),
        business_account_id=str(data.get("business_account_id") or ""),
        access_token=str(data.get("access_token") or ""),
        verify_token=str(data.get("verify_token") or ""),
    )