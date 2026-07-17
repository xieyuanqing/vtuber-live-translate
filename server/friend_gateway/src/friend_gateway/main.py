from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import re
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from typing import AsyncIterator
from urllib.parse import urlencode

import httpx
import uvicorn
import websockets
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field

from .config import Settings
from .store import AuthContext, GatewayError, GatewayStore

LOG = logging.getLogger("friend_gateway")
DEVICE_RE = re.compile(r"^[A-Za-z0-9_-]{32,64}$")
NONCE_RE = re.compile(r"^[A-Za-z0-9_-]{16,128}$")
LIVE_PATH = "/gateway/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()


def _gateway_websocket_close_code(status_code: int) -> int:
    if status_code in (400, 413):
        return 4400
    if status_code == 401:
        return 4401
    if status_code == 403:
        return 4403
    if status_code == 429:
        return 4429
    return 1011


class BindChallengeRequest(BaseModel):
    inviteCode: str = Field(min_length=8, max_length=64)
    devicePublicKey: str = Field(min_length=64, max_length=512)
    appVersion: str = Field(default="unknown", max_length=64)


class BindCompleteRequest(BaseModel):
    challengeId: str = Field(min_length=12, max_length=128)
    signature: str = Field(min_length=32, max_length=512)
    appVersion: str = Field(default="unknown", max_length=64)


class BindLimiter:
    def __init__(self, attempts: int = 10, window_seconds: int = 60) -> None:
        self.attempts = attempts
        self.window_seconds = window_seconds
        self.entries: dict[str, deque[float]] = defaultdict(deque)

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        values = self.entries[key]
        while values and values[0] <= now - self.window_seconds:
            values.popleft()
        if len(values) >= self.attempts:
            return False
        values.append(now)
        return True


def _b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _device_key(public_key_text: str) -> tuple[ec.EllipticCurvePublicKey, str]:
    try:
        der = _b64url_decode(public_key_text)
        key = serialization.load_der_public_key(der)
    except (ValueError, TypeError):
        raise GatewayError(400, "device_key_invalid", "设备公钥格式无效") from None
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise GatewayError(400, "device_key_invalid", "设备公钥算法不受支持")
    device_id = base64.urlsafe_b64encode(hashlib.sha256(der).digest()).decode().rstrip("=")
    return key, device_id


def _verify_signature(public_key_text: str, signature_text: str, message: bytes) -> None:
    key, _ = _device_key(public_key_text)
    try:
        signature = _b64url_decode(signature_text)
        key.verify(signature, message, ec.ECDSA(hashes.SHA256()))
    except (ValueError, InvalidSignature):
        raise GatewayError(401, "signature_invalid", "设备签名无效") from None


def _bind_message(challenge_id: str, nonce: str, device_id: str) -> bytes:
    return f"LTG-BIND-V1\n{challenge_id}\n{nonce}\n{device_id}".encode()


def _request_message(
    timestamp: str,
    nonce: str,
    method: str,
    path: str,
    body: bytes,
    token: str,
) -> bytes:
    return (
        "LTG-REQ-V1\n"
        f"{timestamp}\n{nonce}\n{method.upper()}\n{path}\n"
        f"{hashlib.sha256(body).hexdigest()}\n"
        f"{hashlib.sha256(token.encode()).hexdigest()}"
    ).encode()


def _bearer(headers: object) -> str:
    value = getattr(headers, "get")("authorization", "")
    if not value.lower().startswith("bearer "):
        return ""
    return value[7:].strip()


def _client_ip(request: Request) -> str:
    direct = request.client.host if request.client else "unknown"
    if direct in {"127.0.0.1", "::1"}:
        cf_ip = request.headers.get("cf-connecting-ip", "").strip()
        if cf_ip:
            return cf_ip[:64]
        forwarded = request.headers.get("x-forwarded-for", "").split(",", 1)[0].strip()
        if forwarded:
            return forwarded[:64]
    return direct[:64]


def _authenticate(
    store: GatewayStore,
    headers: object,
    method: str,
    path: str,
    body: bytes = b"",
) -> AuthContext:
    token = _bearer(headers)
    auth = store.authenticate(token)
    device_id = getattr(headers, "get")("x-device-id", "").strip()
    timestamp = getattr(headers, "get")("x-device-time", "").strip()
    nonce = getattr(headers, "get")("x-device-nonce", "").strip()
    signature = getattr(headers, "get")("x-device-signature", "").strip()
    if not DEVICE_RE.fullmatch(device_id) or not hmac.compare_digest(device_id, auth.device_id):
        raise GatewayError(401, "device_mismatch", "凭据与当前设备不匹配")
    try:
        device_time = int(timestamp)
    except ValueError:
        raise GatewayError(401, "signature_required", "缺少设备请求签名") from None
    if abs(int(time.time()) - device_time) > 90:
        raise GatewayError(401, "signature_expired", "设备时间与服务器相差过大")
    if not NONCE_RE.fullmatch(nonce) or not signature:
        raise GatewayError(401, "signature_required", "缺少设备请求签名")
    _verify_signature(
        auth.device_public_key,
        signature,
        _request_message(timestamp, nonce, method, path, body, token),
    )
    store.consume_nonce(auth.invite_id, nonce)
    return auth


def _validate_generate_body(body: bytes) -> None:
    try:
        payload = json.loads(body)
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise GatewayError(400, "json_invalid", "请求 JSON 无效") from None
    if not isinstance(payload, dict):
        raise GatewayError(400, "json_invalid", "请求 JSON 无效")
    allowed = {"systemInstruction", "contents", "tools", "generationConfig"}
    if set(payload) - allowed:
        raise GatewayError(400, "request_not_allowed", "请求包含未开放的 Gemini 功能")
    contents = payload.get("contents")
    if not isinstance(contents, list) or not 1 <= len(contents) <= 16:
        raise GatewayError(400, "contents_invalid", "内容格式无效")
    for content in contents:
        if not isinstance(content, dict) or set(content) - {"role", "parts"}:
            raise GatewayError(400, "contents_invalid", "内容格式无效")
        parts = content.get("parts")
        if not isinstance(parts, list) or not parts:
            raise GatewayError(400, "contents_invalid", "内容格式无效")
        if any(not isinstance(part, dict) or set(part) != {"text"} for part in parts):
            raise GatewayError(400, "request_not_allowed", "只允许文本内容")
    system = payload.get("systemInstruction")
    if system is not None:
        if not isinstance(system, dict) or set(system) - {"parts"}:
            raise GatewayError(400, "system_invalid", "系统指令格式无效")
        parts = system.get("parts")
        if not isinstance(parts, list) or any(
            not isinstance(part, dict) or set(part) != {"text"} for part in parts
        ):
            raise GatewayError(400, "system_invalid", "系统指令格式无效")
    tools = payload.get("tools")
    if tools is not None and tools != [{"googleSearch": {}}]:
        raise GatewayError(400, "tools_not_allowed", "只开放 Google Search 辅助分析")
    generation = payload.get("generationConfig")
    if generation is not None:
        if not isinstance(generation, dict) or set(generation) - {
            "temperature",
            "maxOutputTokens",
            "responseMimeType",
            "responseSchema",
        }:
            raise GatewayError(400, "generation_invalid", "生成参数包含未开放字段")


def _validate_live_setup(text: str, allowed_models: tuple[str, ...]) -> None:
    if len(text.encode()) > 262_144:
        raise GatewayError(413, "setup_too_large", "实时翻译初始化内容过大")
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        raise GatewayError(400, "setup_invalid", "实时翻译初始化 JSON 无效") from None
    if not isinstance(payload, dict) or set(payload) != {"setup"}:
        raise GatewayError(400, "setup_invalid", "第一条消息必须是 Gemini setup")
    setup = payload["setup"]
    if not isinstance(setup, dict) or set(setup) - {
        "model",
        "generationConfig",
        "inputAudioTranscription",
        "outputAudioTranscription",
        "systemInstruction",
    }:
        raise GatewayError(400, "setup_not_allowed", "实时翻译初始化包含未开放功能")
    if setup.get("model") not in allowed_models:
        raise GatewayError(400, "model_not_allowed", "该实时翻译模型未开放")
    generation = setup.get("generationConfig")
    if not isinstance(generation, dict) or set(generation) - {
        "responseModalities",
        "translationConfig",
    }:
        raise GatewayError(400, "setup_invalid", "实时翻译生成参数无效")
    if generation.get("responseModalities") != ["AUDIO"]:
        raise GatewayError(400, "setup_invalid", "实时翻译输出模式无效")
    translation = generation.get("translationConfig")
    if not isinstance(translation, dict) or set(translation) - {
        "targetLanguageCode",
        "echoTargetLanguage",
    }:
        raise GatewayError(400, "setup_invalid", "实时翻译语言参数无效")


def _validate_live_message(text: str) -> None:
    if len(text.encode()) > 524_288:
        raise GatewayError(413, "message_too_large", "实时翻译消息过大")
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        raise GatewayError(400, "message_invalid", "实时翻译消息 JSON 无效") from None
    if not isinstance(payload, dict) or len(payload) != 1:
        raise GatewayError(400, "message_invalid", "实时翻译消息格式无效")
    if next(iter(payload)) not in {"realtimeInput", "clientContent", "toolResponse"}:
        raise GatewayError(400, "message_not_allowed", "实时翻译消息类型未开放")


def create_app(settings: Settings) -> FastAPI:
    store = GatewayStore(settings)
    limiter = BindLimiter()
    active_live: set[int] = set()
    active_lock = asyncio.Lock()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.http = httpx.AsyncClient(
            timeout=httpx.Timeout(connect=20, read=90, write=30, pool=20),
            follow_redirects=False,
        )
        yield
        await app.state.http.aclose()

    app = FastAPI(
        title="LiveTranslate Friend Gateway",
        version="0.2.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.store = store

    @app.exception_handler(GatewayError)
    async def gateway_error(_: Request, error: GatewayError) -> JSONResponse:
        return JSONResponse(
            status_code=error.status_code,
            content={"error": {"code": error.code, "message": error.message}},
        )

    @app.get("/healthz")
    async def health() -> dict[str, object]:
        text_ready = bool(settings.text_api_key)
        live_ready = bool(settings.live_gemini_api_key)
        database_ready = store.ping()
        return {
            "status": "ok" if text_ready and live_ready and database_ready else "degraded",
            "database": "ok" if database_ready else "error",
            "textUpstream": "configured" if text_ready else "missing",
            "liveUpstream": "configured" if live_ready else "missing",
        }

    @app.post("/api/v1/bind/challenge")
    async def bind_challenge(
        payload: BindChallengeRequest,
        request: Request,
    ) -> dict[str, object]:
        if not limiter.allow(_client_ip(request)):
            raise GatewayError(429, "bind_rate_limited", "邀请码尝试过于频繁，请稍后再试")
        _, device_id = _device_key(payload.devicePublicKey)
        challenge = store.create_challenge(
            payload.inviteCode,
            device_id,
            payload.devicePublicKey,
        )
        return {
            "challengeId": challenge.challenge_id,
            "nonce": challenge.nonce,
            "deviceId": challenge.device_id,
            "expiresAt": challenge.expires_at,
        }

    @app.post("/api/v1/bind")
    async def bind_complete(
        payload: BindCompleteRequest,
        request: Request,
    ) -> dict[str, object]:
        if not limiter.allow(_client_ip(request)):
            raise GatewayError(429, "bind_rate_limited", "邀请码尝试过于频繁，请稍后再试")
        challenge = store.get_challenge(payload.challengeId)
        _verify_signature(
            challenge.device_public_key,
            payload.signature,
            _bind_message(challenge.challenge_id, challenge.nonce, challenge.device_id),
        )
        binding = store.complete_binding(challenge.challenge_id)
        return {
            "accessToken": binding.access_token,
            "tokenExpiresAt": binding.expires_at,
            "label": binding.label,
            "gatewayBaseUrl": settings.public_base_url,
        }

    @app.get("/api/v1/status")
    async def status(request: Request) -> dict[str, object]:
        auth = _authenticate(store, request.headers, "GET", request.url.path)
        used = store.usage(auth.invite_id)
        return {
            "bound": True,
            "label": auth.label,
            "tokenExpiresAt": auth.token_expires_at,
            "usage": {
                "textRequests": used["text_requests"],
                "liveSessions": used["live_sessions"],
                "liveBytes": used["live_bytes"],
            },
            "limits": {
                "textRequests": settings.text_requests_per_day,
                "liveSessions": settings.live_sessions_per_day,
                "liveBytes": settings.live_bytes_per_day,
            },
        }

    @app.post("/gateway/v1beta/models/{model}:generateContent")
    async def generate(model: str, request: Request) -> Response:
        content_length = int(request.headers.get("content-length", "0") or 0)
        if content_length > 262_144:
            raise GatewayError(413, "body_too_large", "请求内容过大")
        body = await request.body()
        if len(body) > 262_144:
            raise GatewayError(413, "body_too_large", "请求内容过大")
        auth = _authenticate(store, request.headers, "POST", request.url.path, body)
        if not settings.text_api_key:
            raise GatewayError(503, "gateway_not_ready", "好友测试服务器尚未配置文本模型")
        if model not in settings.allowed_text_models:
            raise GatewayError(400, "model_not_allowed", "该文本模型未开放")
        _validate_generate_body(body)
        store.consume(
            auth.invite_id,
            "text_requests",
            1,
            settings.text_requests_per_day,
        )
        upstream_url = f"{settings.text_upstream}/models/{model}:generateContent"
        try:
            upstream = await request.app.state.http.post(
                upstream_url,
                headers={
                    "content-type": "application/json",
                    "x-goog-api-key": settings.text_api_key,
                },
                content=body,
            )
        except httpx.HTTPError as error:
            LOG.warning("REST upstream failed: %s", type(error).__name__)
            raise GatewayError(502, "upstream_unavailable", "模型服务连接失败") from None
        content_type = upstream.headers.get("content-type", "application/json")
        return Response(
            content=upstream.content,
            status_code=upstream.status_code,
            media_type=content_type.split(";", 1)[0],
        )

    @app.websocket(LIVE_PATH)
    async def live(websocket: WebSocket) -> None:
        token = _bearer(websocket.headers)
        try:
            auth = _authenticate(store, websocket.headers, "GET", websocket.url.path)
        except GatewayError as error:
            await websocket.accept()
            await websocket.close(
                code=_gateway_websocket_close_code(error.status_code),
                reason=error.message,
            )
            return
        if not settings.live_gemini_api_key:
            await websocket.accept()
            await websocket.close(code=1013, reason="live gateway not ready")
            return
        async with active_lock:
            if auth.invite_id in active_live:
                await websocket.accept()
                await websocket.close(code=4429, reason="only one live session is allowed")
                return
            used = store.usage(auth.invite_id)
            if used["live_bytes"] >= settings.live_bytes_per_day:
                await websocket.accept()
                await websocket.close(code=4429, reason="daily live quota exceeded")
                return
            active_live.add(auth.invite_id)
        await websocket.accept()
        upstream = None
        try:
            try:
                setup_message = await asyncio.wait_for(websocket.receive_text(), timeout=5)
                _validate_live_setup(setup_message, settings.allowed_live_models)
            except asyncio.TimeoutError:
                await websocket.close(code=4408, reason="setup timeout")
                return
            except GatewayError as error:
                await websocket.close(
                    code=_gateway_websocket_close_code(error.status_code),
                    reason=error.message,
                )
                return
            setup_bytes = len(setup_message.encode())
            try:
                store.reserve_live(
                    auth.invite_id,
                    session_bytes=setup_bytes,
                    session_limit=settings.live_sessions_per_day,
                    bytes_limit=settings.live_bytes_per_day,
                )
            except GatewayError as error:
                await websocket.close(
                    code=_gateway_websocket_close_code(error.status_code),
                    reason=error.message,
                )
                return
            upstream_url = (
                settings.live_upstream.rstrip("/")
                + "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?"
                + urlencode({"key": settings.live_gemini_api_key})
            )
            try:
                upstream = await websockets.connect(
                    upstream_url,
                    open_timeout=20,
                    close_timeout=5,
                    ping_interval=20,
                    max_size=4 * 1024 * 1024,
                )
            except Exception as error:
                LOG.warning("Live upstream connect failed: %s", type(error).__name__)
                await websocket.close(code=1013, reason="model service unavailable")
                return
            await upstream.send(setup_message)

            async def client_to_upstream() -> None:
                while True:
                    message = await websocket.receive()
                    if message["type"] == "websocket.disconnect":
                        return
                    if message.get("text") is not None:
                        text = message["text"]
                        _validate_live_message(text)
                        store.consume(
                            auth.invite_id,
                            "live_bytes",
                            len(text.encode()),
                            settings.live_bytes_per_day,
                        )
                        await upstream.send(text)
                    elif message.get("bytes") is not None:
                        raise GatewayError(400, "binary_not_supported", "只接受 Gemini JSON 消息")

            async def upstream_to_client() -> None:
                async for message in upstream:
                    if isinstance(message, str):
                        await websocket.send_text(message)
                    else:
                        await websocket.send_bytes(message)

            async def revocation_watchdog() -> None:
                while True:
                    await asyncio.sleep(5)
                    store.authenticate(token)

            tasks = {
                asyncio.create_task(client_to_upstream()),
                asyncio.create_task(upstream_to_client()),
                asyncio.create_task(revocation_watchdog()),
            }
            done, pending = await asyncio.wait(
                tasks,
                timeout=settings.max_live_seconds,
                return_when=asyncio.FIRST_COMPLETED,
            )
            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)
            for task in done:
                error = task.exception()
                if isinstance(error, GatewayError):
                    await websocket.close(
                        code=_gateway_websocket_close_code(error.status_code),
                        reason=error.message,
                    )
                elif error is not None and not isinstance(error, WebSocketDisconnect):
                    LOG.warning("Live relay stopped: %s", type(error).__name__)
            if not done:
                await websocket.close(code=1000, reason="session rotation required")
        finally:
            if upstream is not None:
                await upstream.close()
            async with active_lock:
                active_live.discard(auth.invite_id)

    return app


def run() -> None:
    settings = Settings.from_env()
    uvicorn.run(
        create_app(settings),
        host=settings.host,
        port=settings.port,
        access_log=False,
        proxy_headers=True,
        forwarded_allow_ips="127.0.0.1",
    )
