import secrets
import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlencode

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import HTMLResponse, RedirectResponse

from auth import get_current_user
from config import (
    AUTH_KEYS_DIR,
    AUTH_TOKENS_DB,
    CINEMA_ADMIN_TOKEN,
    CINEMA_BASE_URL,
    CINEMA_WATCH_TOKEN,
    INTEGRATION_ADMINS,
    INTEGRATION_LAUNCH_TTL_SECONDS,
    LIVESTREAM_BASE_URL,
    LIVESTREAM_TEST_WATCH_TOKEN,
    LIVESTREAM_WATCH_TOKEN,
    PUBLIC_BASE_URL,
)
from models import User

router = APIRouter(prefix="/integrations", tags=["integrations"])


@dataclass
class LaunchTicket:
    target_url: str
    expires_at: float
    user_id: str
    mode: str


_launch_tickets: dict[str, LaunchTicket] = {}


def _cleanup_launch_tickets() -> None:
    now = time.time()
    expired = [code for code, ticket in _launch_tickets.items() if ticket.expires_at <= now]
    for code in expired:
        _launch_tickets.pop(code, None)


def _public_url(path: str) -> str:
    return f"{PUBLIC_BASE_URL.rstrip('/')}/{path.lstrip('/')}"


def _cinema_url(path: str = "") -> str:
    if not CINEMA_BASE_URL:
        raise HTTPException(
            status_code=503,
            detail="Cinema base URL is not configured. Set FAMILYHUB_CINEMA_BASE_URL.",
        )
    base = CINEMA_BASE_URL.rstrip("/")
    suffix = path.strip("/")
    return f"{base}/{suffix}" if suffix else f"{base}/"


def _service_url(base_url: str, service_name: str, path: str = "") -> str:
    if not base_url:
        raise HTTPException(
            status_code=503,
            detail=f"{service_name} base URL is not configured.",
        )
    base = base_url.rstrip("/")
    suffix = path.strip("/")
    return f"{base}/{suffix}" if suffix else f"{base}/"


def _livestream_url(path: str = "") -> str:
    return _service_url(LIVESTREAM_BASE_URL, "Livestream", path)


def _read_active_watch_token(room: str) -> str | None:
    db_path = Path(AUTH_TOKENS_DB)
    if not db_path.exists():
        return None

    try:
        with sqlite3.connect(str(db_path)) as conn:
            row = conn.execute(
                """
                SELECT token
                FROM tokens
                WHERE active = 1
                  AND room = ?
                  AND (type = 'watch' OR token_type = 'watch' OR token_type = 'full')
                ORDER BY
                  CASE token_type WHEN 'full' THEN 0 WHEN 'watch' THEN 1 ELSE 2 END,
                  created_at DESC
                LIMIT 1
                """,
                (room,),
            ).fetchone()
    except sqlite3.Error as exc:
        raise HTTPException(status_code=503, detail=f"Unable to read auth token database: {exc}") from exc
    except OSError as exc:
        raise HTTPException(status_code=503, detail=f"Unable to access auth token database: {exc}") from exc

    return row[0] if row else None


def _read_active_cinema_watch_token() -> str | None:
    return _read_active_watch_token("cinema")


def _read_cinema_admin_token() -> str | None:
    key_path = Path(AUTH_KEYS_DIR) / "cinema_admin.key"
    if not key_path.exists():
        return None
    try:
        return key_path.read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise HTTPException(status_code=503, detail=f"Unable to read cinema admin key: {exc}") from exc


def _cinema_watch_token() -> str:
    token = CINEMA_WATCH_TOKEN.strip() or _read_active_cinema_watch_token()
    if not token:
        raise HTTPException(
            status_code=503,
            detail="No active cinema watch token found. Set FAMILYHUB_CINEMA_WATCH_TOKEN or create one in the existing auth system.",
        )
    return token


def _livestream_watch_token(env: str) -> str:
    if env == "live":
        token = LIVESTREAM_WATCH_TOKEN.strip() or _read_active_watch_token("live")
    elif env == "test":
        token = LIVESTREAM_TEST_WATCH_TOKEN.strip() or _read_active_watch_token("test")
    else:
        raise HTTPException(status_code=400, detail="Invalid livestream env. Use live or test.")

    if not token:
        raise HTTPException(
            status_code=503,
            detail=f"No active livestream {env} watch token found. Create one in the existing auth system or set a FamilyHub fallback token.",
        )
    return token


def _cinema_admin_token() -> str:
    token = CINEMA_ADMIN_TOKEN.strip() or _read_cinema_admin_token()
    if not token:
        raise HTTPException(
            status_code=503,
            detail="No cinema admin token found. Set FAMILYHUB_CINEMA_ADMIN_TOKEN or provide /opt/auth/keys/cinema_admin.key.",
        )
    return token


def _is_integration_admin(user: User) -> bool:
    allowed = {item.strip().lower() for item in INTEGRATION_ADMINS.split(",") if item.strip()}
    if not allowed:
        return False
    return user.id.lower() in allowed or user.nickname.lower() in allowed


def _issue_launch_ticket(user: User, target_url: str, mode: str) -> dict[str, object]:
    _cleanup_launch_tickets()
    code = secrets.token_urlsafe(24)
    _launch_tickets[code] = LaunchTicket(
        target_url=target_url,
        expires_at=time.time() + INTEGRATION_LAUNCH_TTL_SECONDS,
        user_id=user.id,
        mode=mode,
    )
    return {
        "launch_url": _public_url(f"integrations/launch/{code}"),
        "expires_in": INTEGRATION_LAUNCH_TTL_SECONDS,
    }


@router.post("/cinema/launch")
def create_cinema_launch(current_user: User = Depends(get_current_user)):
    target_url = f"{_cinema_url()}?{urlencode({'token': _cinema_watch_token()})}"
    return _issue_launch_ticket(current_user, target_url, "cinema_watch")


@router.post("/cinema/admin/launch")
def create_cinema_admin_launch(current_user: User = Depends(get_current_user)):
    if not _is_integration_admin(current_user):
        raise HTTPException(status_code=403, detail="Cinema admin launch is not allowed for this account")
    target_url = f"{_cinema_url('admin')}?{urlencode({'token': _cinema_admin_token()})}"
    return _issue_launch_ticket(current_user, target_url, "cinema_admin")


@router.post("/livestream/launch")
def create_livestream_launch(env: str = "live", current_user: User = Depends(get_current_user)):
    normalized_env = env.strip().lower()
    path = "watch" if normalized_env == "live" else "test-watch"
    token = _livestream_watch_token(normalized_env)
    target_url = f"{_livestream_url(path)}?{urlencode({'token': token})}"
    return _issue_launch_ticket(current_user, target_url, f"livestream_{normalized_env}_watch")


@router.get("/launch/{code}")
def consume_launch_ticket(code: str):
    _cleanup_launch_tickets()
    ticket = _launch_tickets.pop(code, None)
    if not ticket:
        return HTMLResponse(
            "<html><body><h3>FamilyHub launch link expired</h3></body></html>",
            status_code=404,
        )
    return RedirectResponse(ticket.target_url, status_code=302)
