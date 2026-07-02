import base64
import hashlib
import hmac
import json
import time
from typing import Any


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def create_livekit_token(
    *,
    api_key: str,
    api_secret: str,
    room_name: str,
    identity: str,
    name: str,
    can_publish: bool,
    can_subscribe: bool = True,
    ttl_seconds: int = 3600,
) -> str | None:
    if not api_key or not api_secret:
        return None

    now = int(time.time())
    payload: dict[str, Any] = {
        "iss": api_key,
        "sub": identity,
        "name": name,
        "nbf": now - 10,
        "exp": now + ttl_seconds,
        "video": {
            "roomJoin": True,
            "room": room_name,
            "canPublish": can_publish,
            "canSubscribe": can_subscribe,
            "canPublishData": True,
        },
    }
    header = {"alg": "HS256", "typ": "JWT"}

    encoded_header = _b64url(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    encoded_payload = _b64url(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    signing_input = f"{encoded_header}.{encoded_payload}".encode("ascii")
    signature = hmac.new(api_secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    return f"{encoded_header}.{encoded_payload}.{_b64url(signature)}"