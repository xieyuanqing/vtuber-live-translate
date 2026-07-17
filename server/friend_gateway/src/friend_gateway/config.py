from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    host: str
    port: int
    database: Path
    secret_pepper: str
    live_gemini_api_key: str
    text_api_key: str
    text_upstream: str
    public_base_url: str
    allowed_text_models: tuple[str, ...]
    allowed_live_models: tuple[str, ...]
    text_requests_per_day: int
    live_sessions_per_day: int
    live_bytes_per_day: int
    max_live_seconds: int
    token_ttl_days: int = 90
    live_upstream: str = "wss://generativelanguage.googleapis.com"

    @classmethod
    def from_env(cls) -> "Settings":
        pepper = os.environ.get("LTG_SECRET_PEPPER", "")
        if len(pepper) < 32:
            raise RuntimeError("LTG_SECRET_PEPPER must contain at least 32 characters")
        models = tuple(
            item.strip()
            for item in os.environ.get(
                "LTG_ALLOWED_TEXT_MODELS",
                "gemini-3.5-flash",
            ).split(",")
            if item.strip()
        )
        if not models:
            raise RuntimeError("LTG_ALLOWED_TEXT_MODELS cannot be empty")
        live_models = tuple(
            item.strip()
            for item in os.environ.get(
                "LTG_ALLOWED_LIVE_MODELS",
                "models/gemini-3.5-live-translate-preview",
            ).split(",")
            if item.strip()
        )
        if not live_models:
            raise RuntimeError("LTG_ALLOWED_LIVE_MODELS cannot be empty")
        return cls(
            host=os.environ.get("LTG_HOST", "127.0.0.1"),
            port=int(os.environ.get("LTG_PORT", "18766")),
            database=Path(
                os.environ.get(
                    "LTG_DATABASE",
                    "/var/lib/live-translate-friend-gateway/gateway.sqlite3",
                )
            ),
            secret_pepper=pepper,
            live_gemini_api_key=os.environ.get("LTG_LIVE_GEMINI_API_KEY", "").strip(),
            text_api_key=os.environ.get("LTG_TEXT_API_KEY", "").strip(),
            text_upstream=os.environ.get(
                "LTG_TEXT_UPSTREAM",
                "http://127.0.0.1:23000/v1beta",
            ).rstrip("/"),
            public_base_url=os.environ.get(
                "LTG_PUBLIC_BASE_URL",
                "https://translate-test.994431.xyz",
            ).rstrip("/"),
            allowed_text_models=models,
            allowed_live_models=live_models,
            text_requests_per_day=int(os.environ.get("LTG_TEXT_REQUESTS_PER_DAY", "100")),
            live_sessions_per_day=int(os.environ.get("LTG_LIVE_SESSIONS_PER_DAY", "50")),
            live_bytes_per_day=int(os.environ.get("LTG_LIVE_BYTES_PER_DAY", "1073741824")),
            max_live_seconds=int(os.environ.get("LTG_MAX_LIVE_SECONDS", "570")),
            token_ttl_days=int(os.environ.get("LTG_TOKEN_TTL_DAYS", "90")),
        )
