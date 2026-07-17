from __future__ import annotations

import hashlib
import hmac
import secrets
import sqlite3
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .config import Settings


class GatewayError(Exception):
    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message


@dataclass(frozen=True)
class AuthContext:
    invite_id: int
    label: str
    token_expires_at: int
    device_id: str
    device_public_key: str


@dataclass(frozen=True)
class Binding:
    access_token: str
    expires_at: int
    label: str


@dataclass(frozen=True)
class BindChallenge:
    challenge_id: str
    nonce: str
    device_id: str
    device_public_key: str
    expires_at: int


class GatewayStore:
    CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    USAGE_FIELDS = {"text_requests", "live_sessions", "live_bytes"}

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.path = Path(settings.database)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA busy_timeout=10000")
        return conn

    def _initialize(self) -> None:
        with self._connect() as conn:
            conn.executescript(
                """
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS invites (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    code_hash TEXT NOT NULL UNIQUE,
                    code_hint TEXT NOT NULL,
                    label TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER,
                    bound_device_hash TEXT,
                    device_id TEXT,
                    device_public_key TEXT,
                    bound_at INTEGER,
                    token_hash TEXT,
                    token_expires_at INTEGER,
                    revoked_at INTEGER
                );
                CREATE UNIQUE INDEX IF NOT EXISTS one_invite_per_device
                    ON invites(bound_device_hash) WHERE bound_device_hash IS NOT NULL;
                CREATE TABLE IF NOT EXISTS bind_challenges (
                    id TEXT PRIMARY KEY,
                    invite_id INTEGER NOT NULL REFERENCES invites(id) ON DELETE CASCADE,
                    nonce TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    device_public_key TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    used_at INTEGER
                );
                CREATE INDEX IF NOT EXISTS bind_challenges_expiry
                    ON bind_challenges(expires_at);
                CREATE TABLE IF NOT EXISTS auth_nonces (
                    invite_id INTEGER NOT NULL REFERENCES invites(id) ON DELETE CASCADE,
                    nonce_hash TEXT NOT NULL,
                    expires_at INTEGER NOT NULL,
                    PRIMARY KEY(invite_id, nonce_hash)
                ) WITHOUT ROWID;
                CREATE INDEX IF NOT EXISTS auth_nonces_expiry
                    ON auth_nonces(expires_at);
                CREATE TABLE IF NOT EXISTS usage_daily (
                    invite_id INTEGER NOT NULL REFERENCES invites(id) ON DELETE CASCADE,
                    day TEXT NOT NULL,
                    text_requests INTEGER NOT NULL DEFAULT 0,
                    live_sessions INTEGER NOT NULL DEFAULT 0,
                    live_bytes INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (invite_id, day)
                );
                """
            )

    def _digest(self, namespace: str, value: str) -> str:
        return hmac.new(
            self.settings.secret_pepper.encode(),
            f"{namespace}:{value}".encode(),
            hashlib.sha256,
        ).hexdigest()

    @staticmethod
    def _normalize_code(code: str) -> str:
        return "".join(ch for ch in code.upper() if ch.isalnum())

    def _code_hash(self, code: str) -> str:
        return self._digest("invite", self._normalize_code(code))

    def _device_hash(self, device_id: str) -> str:
        return self._digest("device", device_id.strip())

    def _token_hash(self, token: str) -> str:
        return self._digest("token", token.strip())

    @classmethod
    def _new_code(cls) -> str:
        raw = "".join(secrets.choice(cls.CODE_ALPHABET) for _ in range(26))
        return "LT-" + "-".join((raw[:5], raw[5:10], raw[10:15], raw[15:20], raw[20:]))

    @staticmethod
    def _new_token() -> str:
        return "ltg_" + secrets.token_urlsafe(32)

    def create_invites(self, count: int, label: str, expires_days: int) -> list[str]:
        if not 1 <= count <= 100:
            raise ValueError("count must be between 1 and 100")
        now = int(time.time())
        expires_at = now + expires_days * 86400 if expires_days > 0 else None
        codes: list[str] = []
        with self._connect() as conn:
            for _ in range(count):
                while True:
                    code = self._new_code()
                    try:
                        conn.execute(
                            """
                            INSERT INTO invites(code_hash, code_hint, label, created_at, expires_at)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                            (self._code_hash(code), code[-6:], label.strip(), now, expires_at),
                        )
                        codes.append(code)
                        break
                    except sqlite3.IntegrityError:
                        continue
        return codes

    def create_challenge(
        self,
        code: str,
        device_id: str,
        device_public_key: str,
        ttl_seconds: int = 120,
    ) -> BindChallenge:
        now = int(time.time())
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute("DELETE FROM bind_challenges WHERE expires_at<=?", (now,))
            row = conn.execute(
                "SELECT * FROM invites WHERE code_hash=?",
                (self._code_hash(code),),
            ).fetchone()
            if row is None:
                raise GatewayError(404, "invite_invalid", "邀请码无效")
            if row["revoked_at"] is not None:
                raise GatewayError(403, "invite_revoked", "邀请码已停用")
            if row["expires_at"] is not None and row["expires_at"] <= now:
                raise GatewayError(403, "invite_expired", "邀请码已过期")
            device_hash = self._device_hash(device_id)
            bound_device = row["bound_device_hash"]
            if bound_device is not None and not hmac.compare_digest(bound_device, device_hash):
                raise GatewayError(409, "invite_bound", "邀请码已绑定其他设备")
            other = conn.execute(
                "SELECT id FROM invites WHERE bound_device_hash=? AND id<>?",
                (device_hash, row["id"]),
            ).fetchone()
            if other is not None:
                raise GatewayError(409, "device_bound", "当前设备已经绑定其他邀请码")
            challenge_id = "ch_" + secrets.token_urlsafe(18)
            nonce = secrets.token_urlsafe(32)
            expires_at = now + ttl_seconds
            conn.execute(
                """
                INSERT INTO bind_challenges(
                    id, invite_id, nonce, device_id, device_public_key,
                    created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    challenge_id,
                    row["id"],
                    nonce,
                    device_id,
                    device_public_key,
                    now,
                    expires_at,
                ),
            )
            return BindChallenge(
                challenge_id,
                nonce,
                device_id,
                device_public_key,
                expires_at,
            )

    def get_challenge(self, challenge_id: str) -> BindChallenge:
        now = int(time.time())
        with self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM bind_challenges WHERE id=?",
                (challenge_id,),
            ).fetchone()
        if row is None or row["used_at"] is not None or row["expires_at"] <= now:
            raise GatewayError(400, "challenge_invalid", "绑定验证已失效，请重试")
        return BindChallenge(
            row["id"],
            row["nonce"],
            row["device_id"],
            row["device_public_key"],
            int(row["expires_at"]),
        )

    def complete_binding(self, challenge_id: str) -> Binding:
        now = int(time.time())
        token = self._new_token()
        token_hash = self._token_hash(token)
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            challenge = conn.execute(
                "SELECT * FROM bind_challenges WHERE id=?",
                (challenge_id,),
            ).fetchone()
            if (
                challenge is None
                or challenge["used_at"] is not None
                or challenge["expires_at"] <= now
            ):
                raise GatewayError(400, "challenge_invalid", "绑定验证已失效，请重试")
            row = conn.execute(
                "SELECT * FROM invites WHERE id=?",
                (challenge["invite_id"],),
            ).fetchone()
            if row is None or row["revoked_at"] is not None:
                raise GatewayError(403, "invite_revoked", "邀请码已停用")
            if row["expires_at"] is not None and row["expires_at"] <= now:
                raise GatewayError(403, "invite_expired", "邀请码已过期")
            device_hash = self._device_hash(challenge["device_id"])
            bound_device = row["bound_device_hash"]
            if bound_device is not None and not hmac.compare_digest(bound_device, device_hash):
                raise GatewayError(409, "invite_bound", "邀请码已绑定其他设备")
            token_expiry = now + self.settings.token_ttl_days * 86400
            if row["expires_at"] is not None:
                token_expiry = min(token_expiry, int(row["expires_at"]))
            try:
                conn.execute(
                    """
                    UPDATE invites
                    SET bound_device_hash=?, device_id=?, device_public_key=?,
                        bound_at=COALESCE(bound_at, ?), token_hash=?, token_expires_at=?
                    WHERE id=?
                    """,
                    (
                        device_hash,
                        challenge["device_id"],
                        challenge["device_public_key"],
                        now,
                        token_hash,
                        token_expiry,
                        row["id"],
                    ),
                )
            except sqlite3.IntegrityError as exc:
                raise GatewayError(409, "device_bound", "当前设备已经绑定其他邀请码") from exc
            conn.execute(
                "UPDATE bind_challenges SET used_at=? WHERE id=?",
                (now, challenge_id),
            )
            return Binding(token, token_expiry, row["label"])

    def authenticate(self, token: str) -> AuthContext:
        if not token:
            raise GatewayError(401, "auth_required", "需要好友测试凭据")
        now = int(time.time())
        with self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM invites WHERE token_hash=?",
                (self._token_hash(token),),
            ).fetchone()
        if row is None:
            raise GatewayError(401, "auth_invalid", "好友测试凭据无效")
        if row["revoked_at"] is not None:
            raise GatewayError(403, "auth_revoked", "好友测试资格已停用")
        if row["expires_at"] is not None and row["expires_at"] <= now:
            raise GatewayError(403, "invite_expired", "好友测试资格已过期")
        if row["token_expires_at"] is None or row["token_expires_at"] <= now:
            raise GatewayError(401, "token_expired", "好友测试凭据已过期，请重新输入邀请码")
        if not row["device_public_key"] or not row["device_id"]:
            raise GatewayError(401, "device_missing", "设备绑定信息无效")
        return AuthContext(
            int(row["id"]),
            row["label"],
            int(row["token_expires_at"]),
            row["device_id"],
            row["device_public_key"],
        )

    def consume_nonce(self, invite_id: int, nonce: str, ttl_seconds: int = 300) -> None:
        now = int(time.time())
        nonce_hash = self._digest("nonce", nonce)
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute("DELETE FROM auth_nonces WHERE expires_at<=?", (now,))
            try:
                conn.execute(
                    "INSERT INTO auth_nonces(invite_id, nonce_hash, expires_at) VALUES (?, ?, ?)",
                    (invite_id, nonce_hash, now + ttl_seconds),
                )
            except sqlite3.IntegrityError as exc:
                raise GatewayError(401, "nonce_reused", "请求签名已使用") from exc

    @staticmethod
    def _day() -> str:
        return datetime.now(timezone.utc).date().isoformat()

    def usage(self, invite_id: int) -> dict[str, int]:
        with self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM usage_daily WHERE invite_id=? AND day=?",
                (invite_id, self._day()),
            ).fetchone()
        if row is None:
            return {field: 0 for field in self.USAGE_FIELDS}
        return {field: int(row[field]) for field in self.USAGE_FIELDS}

    def reserve_live(
        self,
        invite_id: int,
        session_bytes: int,
        session_limit: int,
        bytes_limit: int,
    ) -> dict[str, int]:
        if session_bytes < 0:
            raise ValueError("session_bytes must be non-negative")
        day = self._day()
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute(
                """
                INSERT INTO usage_daily(invite_id, day) VALUES (?, ?)
                ON CONFLICT(invite_id, day) DO NOTHING
                """,
                (invite_id, day),
            )
            row = conn.execute(
                """
                SELECT live_sessions, live_bytes
                FROM usage_daily WHERE invite_id=? AND day=?
                """,
                (invite_id, day),
            ).fetchone()
            sessions = int(row["live_sessions"])
            live_bytes = int(row["live_bytes"])
            if sessions + 1 > session_limit or live_bytes + session_bytes > bytes_limit:
                raise GatewayError(429, "quota_exceeded", "今日好友测试额度已用完")
            conn.execute(
                """
                UPDATE usage_daily
                SET live_sessions=live_sessions+1, live_bytes=live_bytes+?
                WHERE invite_id=? AND day=?
                """,
                (session_bytes, invite_id, day),
            )
            return {
                "live_sessions": sessions + 1,
                "live_bytes": live_bytes + session_bytes,
            }

    def consume(self, invite_id: int, field: str, amount: int, limit: int) -> int:
        if field not in self.USAGE_FIELDS:
            raise ValueError("unsupported usage field")
        if amount < 0:
            raise ValueError("amount must be non-negative")
        day = self._day()
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute(
                """
                INSERT INTO usage_daily(invite_id, day) VALUES (?, ?)
                ON CONFLICT(invite_id, day) DO NOTHING
                """,
                (invite_id, day),
            )
            used = int(
                conn.execute(
                    f"SELECT {field} FROM usage_daily WHERE invite_id=? AND day=?",
                    (invite_id, day),
                ).fetchone()[0]
            )
            if used + amount > limit:
                raise GatewayError(429, "quota_exceeded", "今日好友测试额度已用完")
            conn.execute(
                f"UPDATE usage_daily SET {field}={field}+? WHERE invite_id=? AND day=?",
                (amount, invite_id, day),
            )
            return used + amount

    def list_invites(self) -> list[dict[str, Any]]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT id, code_hint, label, created_at, expires_at, bound_at,
                       token_expires_at, revoked_at
                FROM invites ORDER BY id DESC
                """
            ).fetchall()
        return [dict(row) for row in rows]

    def revoke(self, code: str) -> bool:
        with self._connect() as conn:
            result = conn.execute(
                "UPDATE invites SET revoked_at=? WHERE code_hash=? AND revoked_at IS NULL",
                (int(time.time()), self._code_hash(code)),
            )
            return result.rowcount == 1

    def reset(self, code: str) -> bool:
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                "SELECT id FROM invites WHERE code_hash=?",
                (self._code_hash(code),),
            ).fetchone()
            if row is None:
                return False
            conn.execute("DELETE FROM bind_challenges WHERE invite_id=?", (row["id"],))
            conn.execute("DELETE FROM auth_nonces WHERE invite_id=?", (row["id"],))
            conn.execute(
                """
                UPDATE invites
                SET bound_device_hash=NULL, device_id=NULL, device_public_key=NULL,
                    bound_at=NULL, token_hash=NULL, token_expires_at=NULL, revoked_at=NULL
                WHERE id=?
                """,
                (row["id"],),
            )
            return True

    def ping(self) -> bool:
        with self._connect() as conn:
            return conn.execute("SELECT 1").fetchone()[0] == 1
