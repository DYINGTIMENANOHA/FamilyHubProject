import logging
import os
import sys
from pathlib import Path
from urllib.parse import urlparse, urlunparse

APP_NAME = "FamilyHub"
API_TITLE = "FamilyHub API"
API_VERSION = "0.3.0"

BASE_DIR = Path(__file__).parent
STORAGE_DIR = Path(os.getenv("FAMILYHUB_STORAGE_DIR", BASE_DIR / "storage"))
UPLOADS_DIR = Path(os.getenv("FAMILYHUB_UPLOADS_DIR", STORAGE_DIR / "uploads"))
DATABASE_URL = os.getenv("FAMILYHUB_DATABASE_URL", f"sqlite:///{BASE_DIR / 'familyhub.db'}")

PUBLIC_BASE_URL = os.getenv("FAMILYHUB_PUBLIC_BASE_URL", "https://YOUR_DOMAIN")
LIVEKIT_URL = os.getenv("FAMILYHUB_LIVEKIT_URL", "wss://YOUR_DOMAIN/livekit")
LIVEKIT_API_KEY = os.getenv("FAMILYHUB_LIVEKIT_API_KEY", "")
LIVEKIT_API_SECRET = os.getenv("FAMILYHUB_LIVEKIT_API_SECRET", "")
LIVEKIT_TOKEN_TTL_SECONDS = int(os.getenv("FAMILYHUB_LIVEKIT_TOKEN_TTL_SECONDS", "3600"))
LIVE_ROOM_STALE_SECONDS = int(os.getenv("FAMILYHUB_LIVE_ROOM_STALE_SECONDS", "7200"))
LIVE_ROOM_CLEANUP_INTERVAL_SECONDS = int(os.getenv("FAMILYHUB_LIVE_ROOM_CLEANUP_INTERVAL_SECONDS", "60"))
LEGACY_LIVESTREAM_BASE_URL = os.getenv("FAMILYHUB_LEGACY_LIVESTREAM_BASE_URL", "")
LEGACY_CINEMA_BASE_URL = os.getenv("FAMILYHUB_LEGACY_CINEMA_BASE_URL", "")


def _sibling_public_url(path: str) -> str:
    parsed = urlparse(PUBLIC_BASE_URL)
    if not parsed.scheme or not parsed.netloc or "YOUR_DOMAIN" in PUBLIC_BASE_URL:
        return ""
    normalized_path = path if path.startswith("/") else f"/{path}"
    return urlunparse((parsed.scheme, parsed.netloc, normalized_path, "", "", ""))


CINEMA_BASE_URL = os.getenv(
    "FAMILYHUB_CINEMA_BASE_URL",
    _sibling_public_url("/cinema/") or LEGACY_CINEMA_BASE_URL,
)
LIVESTREAM_BASE_URL = os.getenv(
    "FAMILYHUB_LIVESTREAM_BASE_URL",
    _sibling_public_url("/") or LEGACY_LIVESTREAM_BASE_URL,
)
AUTH_TOKENS_DB = os.getenv("FAMILYHUB_AUTH_TOKENS_DB", "/opt/auth/data/tokens.db")
AUTH_KEYS_DIR = os.getenv("FAMILYHUB_AUTH_KEYS_DIR", "/opt/auth/keys")
CINEMA_WATCH_TOKEN = os.getenv("FAMILYHUB_CINEMA_WATCH_TOKEN", "")
CINEMA_ADMIN_TOKEN = os.getenv("FAMILYHUB_CINEMA_ADMIN_TOKEN", "")
TEST_CINEMA_BASE_URL = os.getenv(
    "FAMILYHUB_TEST_CINEMA_BASE_URL",
    "https://cn.streamforsoul.com/cinema/",
)
TEST_CINEMA_WATCH_TOKEN = os.getenv(
    "FAMILYHUB_TEST_CINEMA_WATCH_TOKEN",
    "b9a75259ab75f1b80489b291a830081f",
)
TEST_CINEMA_ADMIN_TOKEN = os.getenv(
    "FAMILYHUB_TEST_CINEMA_ADMIN_TOKEN",
    "289e17f38e95263d58e51ed499181698",
)
LIVESTREAM_WATCH_TOKEN = os.getenv("FAMILYHUB_LIVESTREAM_WATCH_TOKEN", "")
LIVESTREAM_TEST_WATCH_TOKEN = os.getenv("FAMILYHUB_LIVESTREAM_TEST_WATCH_TOKEN", "")
INTEGRATION_ADMINS = os.getenv("FAMILYHUB_INTEGRATION_ADMINS", "")
INTEGRATION_LAUNCH_TTL_SECONDS = int(os.getenv("FAMILYHUB_INTEGRATION_LAUNCH_TTL_SECONDS", "120"))

DEFAULT_MAX_DEVICES = int(os.getenv("FAMILYHUB_DEFAULT_MAX_DEVICES", "2"))

UPLOADS_DIR.mkdir(parents=True, exist_ok=True)


def setup_logging() -> None:
    fmt = "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
    logging.basicConfig(
        level=logging.DEBUG,
        format=fmt,
        handlers=[logging.StreamHandler(sys.stdout)],
    )
    logging.getLogger("uvicorn.access").setLevel(logging.INFO)
    logging.getLogger("sqlalchemy.engine").setLevel(logging.WARNING)
    logging.getLogger("passlib").setLevel(logging.WARNING)
    logging.getLogger("passlib.handlers.bcrypt").setLevel(logging.ERROR)
