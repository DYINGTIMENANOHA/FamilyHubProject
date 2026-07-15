import logging
import secrets
import uuid
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from auth import get_current_user
from config import LIVEKIT_API_KEY, LIVEKIT_API_SECRET, LIVEKIT_TOKEN_TTL_SECONDS, LIVEKIT_URL
from database import get_db
from models import LiveRoom, User
from services.live_room_cleanup import cleanup_stale_live_rooms
from services.livekit_tokens import create_livekit_token
from services.account_scope import account_scope_filter, same_account_scope

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/live", tags=["live"])

QUALITY_PATTERN = "^(q1440p60|q1440p30|q1080p60|q1080p30|q720p60|q720p30|ultra|high|hd|standard|smooth|original)$"


class LiveRoomCreate(BaseModel):
    title: str | None = None
    source_type: str = Field(default="camera", pattern="^(camera|screen)$")
    quality: str = Field(default="ultra", pattern=QUALITY_PATTERN)


class LiveRoomQualityUpdate(BaseModel):
    quality: str = Field(pattern=QUALITY_PATTERN)


def _room_payload(room: LiveRoom, owner: User | None = None) -> dict:
    return {
        "id": room.id,
        "title": room.title,
        "owner_id": room.owner_id,
        "owner_nickname": owner.nickname if owner else None,
        "status": room.status,
        "source_type": room.source_type,
        "quality": room.quality,
        "livekit_room_name": room.livekit_room_name,
        "livekit_url": LIVEKIT_URL,
        "livekit_enabled": bool(LIVEKIT_API_KEY and LIVEKIT_API_SECRET),
        "created_at": room.created_at.isoformat() if room.created_at else None,
        "started_at": room.started_at.isoformat() if room.started_at else None,
        "ended_at": room.ended_at.isoformat() if room.ended_at else None,
    }


def _token_payload(room: LiveRoom, user: User, role: str) -> dict:
    publish_allowed = role == "host"
    token = create_livekit_token(
        api_key=LIVEKIT_API_KEY,
        api_secret=LIVEKIT_API_SECRET,
        room_name=room.livekit_room_name,
        identity=user.id,
        name=user.nickname,
        can_publish=publish_allowed,
        can_subscribe=True,
        ttl_seconds=LIVEKIT_TOKEN_TTL_SECONDS,
    )
    if token:
        logger.debug(f"[LIVE] issued LiveKit token room={room.id} user={user.nickname!r} role={role}")
    else:
        logger.info("[LIVE] LiveKit token not issued because API key/secret are not configured")
    return {
        **_room_payload(room),
        "role": role,
        "participant_identity": user.id,
        "participant_name": user.nickname,
        "publish_allowed": publish_allowed,
        "livekit_token": token,
    }


@router.get("/rooms")
def list_rooms(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    cleanup_stale_live_rooms(db)
    rooms = (
        db.query(LiveRoom)
        .join(User, User.id == LiveRoom.owner_id)
        .filter(
            LiveRoom.status == "live",
            account_scope_filter(User.account_type, user),
        )
        .order_by(LiveRoom.created_at.desc())
        .all()
    )
    owners = {
        owner.id: owner
        for owner in db.query(User).filter(User.id.in_([r.owner_id for r in rooms])).all()
    } if rooms else {}
    return [_room_payload(room, owners.get(room.owner_id)) for room in rooms]


@router.post("/rooms", status_code=201)
def create_room(body: LiveRoomCreate, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    cleanup_stale_live_rooms(db)
    existing = db.query(LiveRoom).filter(
        LiveRoom.owner_id == user.id,
        LiveRoom.status == "live",
    ).first()
    if existing:
        logger.info(f"[LIVE] returning existing live room={existing.id} owner={user.nickname!r}")
        return _token_payload(existing, user, "host")

    title = (body.title or f"{user.nickname}'s Live").strip()[:80]
    if not title:
        title = f"{user.nickname}'s Live"

    room = LiveRoom(
        id=str(uuid.uuid4()),
        title=title,
        owner_id=user.id,
        source_type=body.source_type,
        quality=body.quality,
        livekit_room_name=f"familyhub-{secrets.token_hex(8)}",
        status="live",
        started_at=datetime.utcnow(),
        heartbeat_at=datetime.utcnow(),
    )
    db.add(room)
    db.commit()
    db.refresh(room)
    logger.info(f"[LIVE] created room={room.id} owner={user.nickname!r} quality={room.quality}")
    return _token_payload(room, user, "host")


@router.get("/rooms/{room_id}")
def get_room(room_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    cleanup_stale_live_rooms(db)
    room = db.query(LiveRoom).filter(LiveRoom.id == room_id).first()
    if not room:
        raise HTTPException(status_code=404, detail="Live room not found")
    owner = db.query(User).filter(User.id == room.owner_id).first()
    if owner is None or not same_account_scope(user, owner):
        raise HTTPException(status_code=404, detail="Live room not found")
    return _room_payload(room, owner)


@router.post("/rooms/{room_id}/join")
def join_room(room_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    cleanup_stale_live_rooms(db)
    room = db.query(LiveRoom).filter(LiveRoom.id == room_id, LiveRoom.status == "live").first()
    if not room:
        raise HTTPException(status_code=404, detail="Live room not found or already ended")
    owner = db.query(User).filter(User.id == room.owner_id).first()
    if owner is None or not same_account_scope(user, owner):
        raise HTTPException(status_code=404, detail="Live room not found or already ended")
    role = "host" if room.owner_id == user.id else "viewer"
    logger.info(f"[LIVE] join room={room.id} user={user.nickname!r} role={role}")
    return _token_payload(room, user, role)


@router.post("/rooms/{room_id}/heartbeat")
def heartbeat_room(room_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    room = db.query(LiveRoom).filter(
        LiveRoom.id == room_id,
        LiveRoom.owner_id == user.id,
        LiveRoom.status == "live",
    ).first()
    if not room:
        raise HTTPException(status_code=404, detail="Live room not found or already ended")
    room.heartbeat_at = datetime.utcnow()
    db.commit()
    return {"detail": "heartbeat", "room_id": room.id}


@router.patch("/rooms/{room_id}/quality")
def update_room_quality(
    room_id: str,
    body: LiveRoomQualityUpdate,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    room = db.query(LiveRoom).filter(
        LiveRoom.id == room_id,
        LiveRoom.owner_id == user.id,
        LiveRoom.status == "live",
    ).first()
    if not room:
        raise HTTPException(status_code=404, detail="Live room not found or already ended")
    room.quality = body.quality
    room.heartbeat_at = datetime.utcnow()
    db.commit()
    db.refresh(room)
    logger.info(f"[LIVE] updated quality room={room.id} owner={user.nickname!r} quality={room.quality}")
    owner = db.query(User).filter(User.id == room.owner_id).first()
    return _room_payload(room, owner)


@router.post("/rooms/{room_id}/end")
def end_room(room_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    room = db.query(LiveRoom).filter(
        LiveRoom.id == room_id,
        LiveRoom.owner_id == user.id,
    ).first()
    if not room:
        raise HTTPException(status_code=404, detail="Live room not found")
    if room.status != "ended":
        room.status = "ended"
        room.ended_at = datetime.utcnow()
        db.commit()
    logger.info(f"[LIVE] ended room={room.id} owner={user.nickname!r}")
    return {"detail": "ended", "room_id": room.id}
