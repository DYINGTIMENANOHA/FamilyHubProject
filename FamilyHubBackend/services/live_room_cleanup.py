import logging
from datetime import datetime, timedelta

from config import LIVE_ROOM_STALE_SECONDS
from database import SessionLocal
from models import LiveRoom
from sqlalchemy import func

logger = logging.getLogger(__name__)


def cleanup_stale_live_rooms(db=None) -> list[str]:
    """Mark live rooms as ended once the host has stopped sending heartbeats."""
    owns_session = db is None
    session = db or SessionLocal()
    try:
        cutoff = datetime.utcnow() - timedelta(seconds=LIVE_ROOM_STALE_SECONDS)
        stale_rooms = (
            session.query(LiveRoom)
            .filter(
                LiveRoom.status == "live",
                LiveRoom.started_at.isnot(None),
                func.coalesce(LiveRoom.heartbeat_at, LiveRoom.started_at) < cutoff,
            )
            .all()
        )
        if not stale_rooms:
            return []

        ended_at = datetime.utcnow()
        room_ids = []
        for room in stale_rooms:
            room.status = "ended"
            room.ended_at = ended_at
            room_ids.append(room.id)
        session.commit()
        logger.info(
            "[LIVE][CLEANUP] ended %s stale live room(s), ttl=%ss ids=%s",
            len(room_ids),
            LIVE_ROOM_STALE_SECONDS,
            room_ids,
        )
        return room_ids
    finally:
        if owns_session:
            session.close()
