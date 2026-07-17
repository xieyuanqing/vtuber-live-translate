import base64
import hashlib
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from friend_gateway.config import Settings
from friend_gateway.store import GatewayError, GatewayStore


def settings(tmp_path: Path) -> Settings:
    return Settings(
        host="127.0.0.1",
        port=18766,
        database=tmp_path / "gateway.sqlite3",
        secret_pepper="p" * 64,
        live_gemini_api_key="",
        text_api_key="",
        text_upstream="http://127.0.0.1:23000/v1beta",
        public_base_url="https://example.test",
        allowed_text_models=("gemini-2.5-flash",),
        allowed_live_models=("models/gemini-3.5-live-translate-preview",),
        text_requests_per_day=2,
        live_sessions_per_day=1,
        live_bytes_per_day=100,
        max_live_seconds=30,
        token_ttl_days=30,
    )


def device() -> tuple[str, str]:
    private = ec.generate_private_key(ec.SECP256R1())
    der = private.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    public_key = base64.urlsafe_b64encode(der).decode().rstrip("=")
    device_id = base64.urlsafe_b64encode(hashlib.sha256(der).digest()).decode().rstrip("=")
    return public_key, device_id


def complete(store: GatewayStore, code: str, public_key: str, device_id: str):
    challenge = store.create_challenge(code, device_id, public_key)
    return store.complete_binding(challenge.challenge_id)


def test_invite_binds_one_device_and_rotates_same_device_token(tmp_path: Path) -> None:
    store = GatewayStore(settings(tmp_path))
    code = store.create_invites(1, "Alice", 30)[0]
    public_a, device_a = device()
    public_b, device_b = device()
    assert len(GatewayStore._normalize_code(code)) >= 28

    first = complete(store, code, public_a, device_a)
    auth = store.authenticate(first.access_token)
    assert auth.label == "Alice"
    assert auth.device_id == device_a

    with pytest.raises(GatewayError) as other_device:
        store.create_challenge(code, device_b, public_b)
    assert other_device.value.code == "invite_bound"

    second = complete(store, code, public_a, device_a)
    assert second.access_token != first.access_token
    with pytest.raises(GatewayError) as old_token:
        store.authenticate(first.access_token)
    assert old_token.value.code == "auth_invalid"
    assert store.authenticate(second.access_token).invite_id == auth.invite_id


def test_revoke_reset_nonce_and_quota(tmp_path: Path) -> None:
    store = GatewayStore(settings(tmp_path))
    code = store.create_invites(1, "Bob", 30)[0]
    public_a, device_a = device()
    binding = complete(store, code, public_a, device_a)
    auth = store.authenticate(binding.access_token)

    store.consume_nonce(auth.invite_id, "nonce-abcdefghijklmnop")
    with pytest.raises(GatewayError) as replay:
        store.consume_nonce(auth.invite_id, "nonce-abcdefghijklmnop")
    assert replay.value.code == "nonce_reused"

    assert store.consume(auth.invite_id, "text_requests", 1, 2) == 1
    assert store.consume(auth.invite_id, "text_requests", 1, 2) == 2
    with pytest.raises(GatewayError) as quota:
        store.consume(auth.invite_id, "text_requests", 1, 2)
    assert quota.value.code == "quota_exceeded"

    assert store.revoke(code)
    with pytest.raises(GatewayError) as revoked:
        store.authenticate(binding.access_token)
    assert revoked.value.code == "auth_revoked"

    assert store.reset(code)
    public_b, device_b = device()
    rebound = complete(store, code, public_b, device_b)
    assert store.authenticate(rebound.access_token).label == "Bob"


def test_live_reservation_is_atomic_and_preserves_accepted_bytes(tmp_path: Path) -> None:
    store = GatewayStore(settings(tmp_path))
    code = store.create_invites(1, "Live", 30)[0]
    public_key, device_id = device()
    auth = store.authenticate(complete(store, code, public_key, device_id).access_token)

    usage = store.reserve_live(
        auth.invite_id,
        session_bytes=60,
        session_limit=1,
        bytes_limit=100,
    )
    assert usage["live_sessions"] == 1
    assert usage["live_bytes"] == 60

    assert store.consume(auth.invite_id, "live_bytes", 40, 100) == 100
    with pytest.raises(GatewayError) as quota:
        store.consume(auth.invite_id, "live_bytes", 1, 100)
    assert quota.value.code == "quota_exceeded"
    assert store.usage(auth.invite_id)["live_bytes"] == 100

    other_code = store.create_invites(1, "No partial reservation", 30)[0]
    other_public, other_device = device()
    other = store.authenticate(complete(store, other_code, other_public, other_device).access_token)
    with pytest.raises(GatewayError):
        store.reserve_live(
            other.invite_id,
            session_bytes=101,
            session_limit=1,
            bytes_limit=100,
        )
    assert store.usage(other.invite_id)["live_sessions"] == 0
    assert store.usage(other.invite_id)["live_bytes"] == 0
