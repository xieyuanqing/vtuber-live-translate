import base64
import asyncio
import hashlib
import json
import secrets
import time
from dataclasses import replace
from pathlib import Path

import httpx
import pytest
import friend_gateway.main as gateway_main
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from friend_gateway.config import Settings
from friend_gateway.main import _bind_message, _request_message, create_app


def settings(
    tmp_path: Path,
    text_api_key: str = "",
    live_api_key: str = "",
) -> Settings:
    return Settings(
        host="127.0.0.1",
        port=18766,
        database=tmp_path / "gateway.sqlite3",
        secret_pepper="s" * 64,
        live_gemini_api_key=live_api_key,
        text_api_key=text_api_key,
        text_upstream="http://127.0.0.1:23000/v1beta",
        public_base_url="https://translate-test.example",
        allowed_text_models=("gemini-2.5-flash",),
        allowed_live_models=("models/gemini-3.5-live-translate-preview",),
        text_requests_per_day=10,
        live_sessions_per_day=10,
        live_bytes_per_day=1_000_000,
        max_live_seconds=30,
        token_ttl_days=30,
    )


def b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def device() -> tuple[ec.EllipticCurvePrivateKey, str, str]:
    private = ec.generate_private_key(ec.SECP256R1())
    der = private.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return private, b64(der), b64(hashlib.sha256(der).digest())


def sign(private: ec.EllipticCurvePrivateKey, message: bytes) -> str:
    return b64(private.sign(message, ec.ECDSA(hashes.SHA256())))


def bind(client: TestClient, code: str, private, public_key: str) -> str:
    challenge_response = client.post(
        "/api/v1/bind/challenge",
        json={"inviteCode": code, "devicePublicKey": public_key, "appVersion": "test"},
    )
    assert challenge_response.status_code == 200
    challenge = challenge_response.json()
    signature = sign(
        private,
        _bind_message(challenge["challengeId"], challenge["nonce"], challenge["deviceId"]),
    )
    bound = client.post(
        "/api/v1/bind",
        json={
            "challengeId": challenge["challengeId"],
            "signature": signature,
            "appVersion": "test",
        },
    )
    assert bound.status_code == 200
    return bound.json()["accessToken"]


def signed_headers(
    private: ec.EllipticCurvePrivateKey,
    device_id: str,
    token: str,
    method: str,
    path: str,
    body: bytes = b"",
    nonce: str | None = None,
) -> dict[str, str]:
    timestamp = str(int(time.time()))
    nonce = nonce or b64(secrets.token_bytes(18))
    signature = sign(private, _request_message(timestamp, nonce, method, path, body, token))
    return {
        "Authorization": f"Bearer {token}",
        "X-Device-ID": device_id,
        "X-Device-Time": timestamp,
        "X-Device-Nonce": nonce,
        "X-Device-Signature": signature,
    }


def live_setup_message() -> str:
    return json.dumps(
        {
            "setup": {
                "model": "models/gemini-3.5-live-translate-preview",
                "generationConfig": {
                    "responseModalities": ["AUDIO"],
                    "translationConfig": {
                        "targetLanguageCode": "cmn-CN",
                        "echoTargetLanguage": True,
                    },
                },
                "inputAudioTranscription": {},
                "outputAudioTranscription": {},
                "systemInstruction": {"parts": [{"text": "translate"}]},
            }
        },
        separators=(",", ":"),
    )


def test_bind_status_signature_replay_and_gateway_not_ready(tmp_path: Path) -> None:
    app = create_app(settings(tmp_path))
    code = app.state.store.create_invites(1, "friend-a", 30)[0]
    private, public_key, device_id = device()

    with TestClient(app) as client:
        health = client.get("/healthz")
        assert health.status_code == 200
        assert health.json()["status"] == "degraded"

        token = bind(client, code, private, public_key)
        assert token.startswith("ltg_")
        headers = signed_headers(private, device_id, token, "GET", "/api/v1/status")
        status = client.get("/api/v1/status", headers=headers)
        assert status.status_code == 200
        assert status.json()["label"] == "friend-a"

        replay = client.get("/api/v1/status", headers=headers)
        assert replay.status_code == 401
        assert replay.json()["error"]["code"] == "nonce_reused"

        wrong = signed_headers(private, device_id, token, "GET", "/api/v1/status")
        wrong["X-Device-ID"] = "A" * 43
        wrong_device = client.get("/api/v1/status", headers=wrong)
        assert wrong_device.status_code == 401
        assert wrong_device.json()["error"]["code"] == "device_mismatch"

        path = "/gateway/v1beta/models/gemini-2.5-flash:generateContent"
        body = json.dumps({"contents": [{"parts": [{"text": "hello"}]}]}, separators=(",", ":")).encode()
        unavailable = client.post(
            path,
            headers={
                **signed_headers(private, device_id, token, "POST", path, body),
                "Content-Type": "application/json",
            },
            content=body,
        )
        assert unavailable.status_code == 503
        assert unavailable.json()["error"]["code"] == "gateway_not_ready"


def test_invalid_invite_and_invalid_binding_signature(tmp_path: Path) -> None:
    app = create_app(settings(tmp_path))
    private, public_key, _ = device()
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/bind/challenge",
            json={
                "inviteCode": "LT-AAAAA-BBBBB-CCCCC-DDDDD-EEEEEE",
                "devicePublicKey": public_key,
                "appVersion": "test",
            },
        )
        assert response.status_code == 404
        assert response.json()["error"]["code"] == "invite_invalid"

        code = app.state.store.create_invites(1, "friend-b", 30)[0]
        challenge = client.post(
            "/api/v1/bind/challenge",
            json={"inviteCode": code, "devicePublicKey": public_key},
        ).json()
        other_private, _, _ = device()
        invalid = client.post(
            "/api/v1/bind",
            json={
                "challengeId": challenge["challengeId"],
                "signature": sign(
                    other_private,
                    _bind_message(
                        challenge["challengeId"],
                        challenge["nonce"],
                        challenge["deviceId"],
                    ),
                ),
            },
        )
        assert invalid.status_code == 401
        assert invalid.json()["error"]["code"] == "signature_invalid"


def test_text_proxy_keeps_friend_token_away_from_upstream(tmp_path: Path) -> None:
    upstream_requests: list[httpx.Request] = []

    def upstream(request: httpx.Request) -> httpx.Response:
        upstream_requests.append(request)
        return httpx.Response(
            200,
            json={"candidates": [{"content": {"parts": [{"text": '{"ok":true}'}]}}]},
            headers={"Content-Type": "application/json"},
        )

    app = create_app(settings(tmp_path, text_api_key="cch-text-secret"))
    code = app.state.store.create_invites(1, "friend-proxy", 30)[0]
    private, public_key, device_id = device()
    mock_http = httpx.AsyncClient(transport=httpx.MockTransport(upstream))

    with TestClient(app) as client:
        token = bind(client, code, private, public_key)
        original_http = app.state.http
        app.state.http = mock_http
        path = "/gateway/v1beta/models/gemini-2.5-flash:generateContent"
        body = json.dumps(
            {
                "systemInstruction": {"parts": [{"text": "system"}]},
                "contents": [{"role": "user", "parts": [{"text": "hello"}]}],
                "tools": [{"googleSearch": {}}],
                "generationConfig": {
                    "responseMimeType": "application/json",
                    "temperature": 0.2,
                },
            },
            separators=(",", ":"),
        ).encode()
        response = client.post(
            path,
            headers={
                **signed_headers(private, device_id, token, "POST", path, body),
                "Content-Type": "application/json",
            },
            content=body,
        )
        app.state.http = original_http

        assert response.status_code == 200
        assert response.json()["candidates"][0]["content"]["parts"][0]["text"] == '{"ok":true}'
        status_path = "/api/v1/status"
        status = client.get(
            status_path,
            headers=signed_headers(private, device_id, token, "GET", status_path),
        )
        assert status.json()["usage"]["textRequests"] == 1

    asyncio.run(mock_http.aclose())
    assert len(upstream_requests) == 1
    request = upstream_requests[0]
    assert request.headers["x-goog-api-key"] == "cch-text-secret"
    assert "Authorization" not in request.headers
    assert "X-Device-ID" not in request.headers
    assert token not in str(request.url)
    assert request.url.path == "/v1beta/models/gemini-2.5-flash:generateContent"


def test_live_proxy_forwards_setup_and_realtime_messages(tmp_path: Path, monkeypatch) -> None:
    class FakeUpstream:
        def __init__(self) -> None:
            self.sent: list[str] = []
            self.responses: asyncio.Queue[str] = asyncio.Queue()
            self.closed = False

        async def send(self, message: str) -> None:
            self.sent.append(message)
            if len(self.sent) == 1:
                await self.responses.put(json.dumps({"setupComplete": {}}))
            else:
                await self.responses.put(
                    json.dumps(
                        {
                            "serverContent": {
                                "inputTranscription": {"text": "hello"},
                                "outputTranscription": {"text": "你好"},
                            }
                        }
                    )
                )

        def __aiter__(self):
            return self

        async def __anext__(self) -> str:
            return await self.responses.get()

        async def close(self) -> None:
            self.closed = True

    fake = FakeUpstream()
    upstream_urls: list[str] = []

    async def fake_connect(url: str, **_kwargs):
        upstream_urls.append(url)
        return fake

    monkeypatch.setattr(gateway_main.websockets, "connect", fake_connect)
    app = create_app(settings(tmp_path, live_api_key="google-live-secret"))
    code = app.state.store.create_invites(1, "friend-live", 30)[0]
    private, public_key, device_id = device()
    path = "/gateway/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    with TestClient(app) as client:
        token = bind(client, code, private, public_key)
        headers = signed_headers(private, device_id, token, "GET", path)
        with client.websocket_connect(path, headers=headers) as websocket:
            setup = json.dumps(
                {
                    "setup": {
                        "model": "models/gemini-3.5-live-translate-preview",
                        "generationConfig": {
                            "responseModalities": ["AUDIO"],
                            "translationConfig": {
                                "targetLanguageCode": "cmn-CN",
                                "echoTargetLanguage": True,
                            },
                        },
                        "inputAudioTranscription": {},
                        "outputAudioTranscription": {},
                        "systemInstruction": {"parts": [{"text": "translate"}]},
                    }
                },
                separators=(",", ":"),
            )
            websocket.send_text(setup)
            assert websocket.receive_json() == {"setupComplete": {}}
            realtime = json.dumps(
                {
                    "realtimeInput": {
                        "audio": {"data": "AA==", "mimeType": "audio/pcm;rate=16000"}
                    }
                },
                separators=(",", ":"),
            )
            websocket.send_text(realtime)
            translated = websocket.receive_json()
            assert translated["serverContent"]["outputTranscription"]["text"] == "你好"

        status_path = "/api/v1/status"
        status = client.get(
            status_path,
            headers=signed_headers(private, device_id, token, "GET", status_path),
        )
        assert status.json()["usage"]["liveSessions"] == 1
        assert status.json()["usage"]["liveBytes"] >= len(setup.encode())

    assert fake.closed is True
    assert len(fake.sent) == 2
    assert upstream_urls and "key=google-live-secret" in upstream_urls[0]
    assert token not in upstream_urls[0]


def test_live_session_quota_rejects_before_upstream_connect(tmp_path: Path, monkeypatch) -> None:
    upstream_connects: list[str] = []

    async def fake_connect(url: str, **_kwargs):
        upstream_connects.append(url)
        raise AssertionError("quota rejection must happen before upstream connect")

    monkeypatch.setattr(gateway_main.websockets, "connect", fake_connect)
    app = create_app(
        replace(
            settings(tmp_path, live_api_key="google-live-secret"),
            live_sessions_per_day=1,
        )
    )
    code = app.state.store.create_invites(1, "friend-live-quota", 30)[0]
    private, public_key, device_id = device()
    path = "/gateway/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    with TestClient(app) as client:
        token = bind(client, code, private, public_key)
        invite_id = app.state.store.authenticate(token).invite_id
        app.state.store.consume(invite_id, "live_sessions", 1, 1)
        headers = signed_headers(private, device_id, token, "GET", path)
        with pytest.raises(WebSocketDisconnect) as closed:
            with client.websocket_connect(path, headers=headers) as websocket:
                websocket.send_text(live_setup_message())
                websocket.receive_text()
        assert closed.value.code == 4429

    assert upstream_connects == []


def test_live_byte_quota_reserves_each_message_before_forwarding(tmp_path: Path, monkeypatch) -> None:
    class FakeUpstream:
        def __init__(self) -> None:
            self.sent: list[str] = []
            self.responses: asyncio.Queue[str] = asyncio.Queue()
            self.closed = False

        async def send(self, message: str) -> None:
            self.sent.append(message)
            if len(self.sent) == 1:
                await self.responses.put(json.dumps({"setupComplete": {}}))
            else:
                await self.responses.put(
                    json.dumps({"serverContent": {"outputTranscription": {"text": "你好"}}})
                )

        def __aiter__(self):
            return self

        async def __anext__(self) -> str:
            return await self.responses.get()

        async def close(self) -> None:
            self.closed = True

    fake = FakeUpstream()

    async def fake_connect(_url: str, **_kwargs):
        return fake

    monkeypatch.setattr(gateway_main.websockets, "connect", fake_connect)
    setup = live_setup_message()
    realtime = json.dumps(
        {"realtimeInput": {"audio": {"data": "AA==", "mimeType": "audio/pcm;rate=16000"}}},
        separators=(",", ":"),
    )
    accepted_bytes = len(setup.encode()) + len(realtime.encode())
    app = create_app(
        replace(
            settings(tmp_path, live_api_key="google-live-secret"),
            live_sessions_per_day=5,
            live_bytes_per_day=accepted_bytes,
        )
    )
    code = app.state.store.create_invites(1, "friend-live-bytes", 30)[0]
    private, public_key, device_id = device()
    path = "/gateway/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    with TestClient(app) as client:
        token = bind(client, code, private, public_key)
        headers = signed_headers(private, device_id, token, "GET", path)
        with client.websocket_connect(path, headers=headers) as websocket:
            websocket.send_text(setup)
            assert websocket.receive_json() == {"setupComplete": {}}
            websocket.send_text(realtime)
            translated = websocket.receive_json()
            assert translated["serverContent"]["outputTranscription"]["text"] == "你好"
            websocket.send_text(realtime)
            with pytest.raises(WebSocketDisconnect) as closed:
                websocket.receive_text()
            assert closed.value.code == 4429

        invite_id = app.state.store.authenticate(token).invite_id
        usage = app.state.store.usage(invite_id)
        assert usage["live_sessions"] == 1
        assert usage["live_bytes"] == accepted_bytes

    assert fake.sent == [setup, realtime]
    assert fake.closed is True


@pytest.mark.parametrize("bad_message", ["invalid_json", "binary"])
def test_live_invalid_client_message_closes_as_configuration_error(
    tmp_path: Path,
    monkeypatch,
    bad_message: str,
) -> None:
    class FakeUpstream:
        def __init__(self) -> None:
            self.sent: list[str] = []
            self.responses: asyncio.Queue[str] = asyncio.Queue()

        async def send(self, message: str) -> None:
            self.sent.append(message)
            if len(self.sent) == 1:
                await self.responses.put(json.dumps({"setupComplete": {}}))

        def __aiter__(self):
            return self

        async def __anext__(self) -> str:
            return await self.responses.get()

        async def close(self) -> None:
            return None

    fake = FakeUpstream()

    async def fake_connect(_url: str, **_kwargs):
        return fake

    monkeypatch.setattr(gateway_main.websockets, "connect", fake_connect)
    app = create_app(settings(tmp_path, live_api_key="google-live-secret"))
    code = app.state.store.create_invites(1, f"friend-live-{bad_message}", 30)[0]
    private, public_key, device_id = device()
    path = "/gateway/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    setup = live_setup_message()

    with TestClient(app) as client:
        token = bind(client, code, private, public_key)
        headers = signed_headers(private, device_id, token, "GET", path)
        with client.websocket_connect(path, headers=headers) as websocket:
            websocket.send_text(setup)
            assert websocket.receive_json() == {"setupComplete": {}}
            if bad_message == "binary":
                websocket.send_bytes(b"not-json")
            else:
                websocket.send_text("{")
            with pytest.raises(WebSocketDisconnect) as closed:
                websocket.receive_text()
            assert closed.value.code == 4400

    assert fake.sent == [setup]
