import logging
import os
import sys
from pathlib import Path

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
LEGACY_LIVESTREAM_BASE_URL = os.getenv("FAMILYHUB_LEGACY_LIVESTREAM_BASE_URL", "")
LEGACY_CINEMA_BASE_URL = os.getenv("FAMILYHUB_LEGACY_CINEMA_BASE_URL", "")

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