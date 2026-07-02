import logging
import secrets
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from auth import get_current_user
from config import DEFAULT_MAX_DEVICES
from database import get_db
from models import AccountSession, Friendship, FriendRequest, User, UserDevice
from services.sync_manager import manager

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/users", tags=["users"])

try:
    from passlib.context import CryptContext
    _pwd_ctx = CryptContext(schemes=["bcrypt"], deprecated="auto")
    logger.info("[AUTH] passlib bcrypt loaded")
except ImportError:
    _pwd_ctx = None
    logger.error("[AUTH] passlib not installed - run: pip install passlib[bcrypt]")


def _hash_password(plain: str) -> str:
    if _pwd_ctx is None:
        raise HTTPException(status_code=500, detail="passlib not installed on server")
    return _pwd_ctx.hash(plain)


def _verify_password(plain: str, hashed: str) -> bool:
    if _pwd_ctx is None:
        return False
    return _pwd_ctx.verify(plain, hashed)


class RegisterRequest(BaseModel):
    nickname: str
    password: str


class LoginRequest(BaseModel):
    nickname: str
    password: str
    device_id: str
    device_name: str | None = None
    platform: str | None = "android"


class FriendRequestCreate(BaseModel):
    nickname: str


@router.post("/register", status_code=410)
def register_disabled():
    raise HTTPException(
        status_code=410,
        detail="Self registration is disabled. Ask the FamilyHub admin to create an account.",
    )


@router.post("/login")
def login(body: LoginRequest, db: Session = Depends(get_db)):
    nickname = body.nickname.strip()
    device_id = body.device_id.strip()
    logger.info(f"[USER] login attempt: nickname={nickname!r} device_id={device_id[:12]!r}")

    if not device_id:
        raise HTTPException(status_code=400, detail="device_id is required")

    user = db.query(User).filter(User.nickname == nickname).first()
    if not user or user.status != "active":
        logger.warning(f"[USER] login fail: nickname={nickname!r} not found or disabled")
        raise HTTPException(status_code=401, detail="Invalid nickname or password")

    if not user.password_hash:
        logger.warning(f"[USER] login fail: user={user.id} has no password_hash")
        raise HTTPException(status_code=401, detail="Account has no password set, contact admin")

    if not _verify_password(body.password, user.password_hash):
        logger.warning(f"[USER] login fail: wrong password for nickname={nickname!r}")
        raise HTTPException(status_code=401, detail="Invalid nickname or password")

    max_devices = user.max_devices or DEFAULT_MAX_DEVICES
    device = db.query(UserDevice).filter(
        UserDevice.user_id == user.id,
        UserDevice.device_id == device_id,
    ).first()

    if not device:
        active_count = db.query(UserDevice).filter(
            UserDevice.user_id == user.id,
            UserDevice.active.is_(True),
        ).count()
        if active_count >= max_devices:
            logger.warning(
                f"[USER] login fail: device limit user={user.id} active={active_count} max={max_devices}"
            )
            raise HTTPException(status_code=403, detail={
                "code": "DEVICE_LIMIT_REACHED",
                "message": f"This account allows {max_devices} active device(s).",
                "max_devices": max_devices,
            })
        device = UserDevice(
            user_id=user.id,
            device_id=device_id,
            device_name=body.device_name,
            platform=body.platform,
            active=True,
            last_seen_at=datetime.utcnow(),
        )
        db.add(device)
    else:
        device.active = True
        device.device_name = body.device_name or device.device_name
        device.platform = body.platform or device.platform
        device.last_seen_at = datetime.utcnow()

    token = secrets.token_hex(32)
    session = AccountSession(
        token=token,
        user_id=user.id,
        device_id=device_id,
        last_seen_at=datetime.utcnow(),
    )
    db.add(session)
    db.commit()

    logger.info(f"[USER] login success: id={user.id} nickname={user.nickname!r} device_id={device_id[:12]!r}")
    return {
        "id": user.id,
        "nickname": user.nickname,
        "token": token,
        "device_id": device_id,
        "max_devices": max_devices,
    }


@router.get("/me")
def me(user: User = Depends(get_current_user)):
    return {
        "id": user.id,
        "nickname": user.nickname,
        "status": user.status,
        "max_devices": user.max_devices,
    }

@router.get("/search")
def search_users(q: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    logger.debug(f"[USER] search: q={q!r} by={user.id}")
    if len(q.strip()) < 1:
        raise HTTPException(status_code=400, detail="Query too short")
    results = (
        db.query(User)
        .filter(User.nickname.ilike(f"%{q}%"), User.id != user.id)
        .limit(20)
        .all()
    )
    logger.info(f"[USER] search: q={q!r} results={len(results)}")
    return [{"id": u.id, "nickname": u.nickname} for u in results]


# Friend Requests

@router.post("/friend-requests", status_code=201)
def send_friend_request(body: FriendRequestCreate, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    logger.info(f"[FRIEND] request: from={user.id} to_nickname={body.nickname!r}")

    target = db.query(User).filter(User.nickname == body.nickname).first()
    if not target:
        raise HTTPException(status_code=404, detail="User not found")
    if target.id == user.id:
        raise HTTPException(status_code=400, detail="Cannot send friend request to yourself")

    # Already friends?
    already_friends = db.query(Friendship).filter(
        Friendship.user_id == user.id,
        Friendship.friend_id == target.id,
    ).first()
    if already_friends:
        raise HTTPException(status_code=409, detail="Already friends")

    # Pending request already exists?
    pending = db.query(FriendRequest).filter(
        FriendRequest.from_user_id == user.id,
        FriendRequest.to_user_id == target.id,
        FriendRequest.status == "pending",
    ).first()
    if pending:
        raise HTTPException(status_code=409, detail="Friend request already pending")

    req = FriendRequest(from_user_id=user.id, to_user_id=target.id)
    db.add(req)
    db.commit()
    db.refresh(req)
    logger.info(f"[FRIEND] request created: id={req.id} from={user.nickname!r} to={target.nickname!r}")
    return {"id": req.id, "to_user_id": target.id, "to_nickname": target.nickname, "status": req.status}


@router.get("/friend-requests")
def list_friend_requests(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    """List incoming pending friend requests."""
    logger.debug(f"[FRIEND] list requests for user={user.id}")
    reqs = db.query(FriendRequest).filter(
        FriendRequest.to_user_id == user.id,
        FriendRequest.status == "pending",
    ).all()
    result = []
    for r in reqs:
        sender = db.query(User).filter(User.id == r.from_user_id).first()
        result.append({
            "id": r.id,
            "from_user_id": r.from_user_id,
            "from_nickname": sender.nickname if sender else "?",
            "created_at": r.created_at.isoformat(),
        })
    logger.info(f"[FRIEND] pending incoming requests for user={user.nickname!r}: count={len(result)}")
    return result


@router.post("/friend-requests/{request_id}/accept")
def accept_friend_request(request_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    logger.info(f"[FRIEND] accept: request_id={request_id} by={user.id}")

    req = db.query(FriendRequest).filter(
        FriendRequest.id == request_id,
        FriendRequest.to_user_id == user.id,
        FriendRequest.status == "pending",
    ).first()
    if not req:
        raise HTTPException(status_code=404, detail="Friend request not found")

    req.status = "accepted"

    # Create bidirectional friendship
    db.add(Friendship(user_id=req.from_user_id, friend_id=req.to_user_id))
    db.add(Friendship(user_id=req.to_user_id, friend_id=req.from_user_id))
    db.commit()

    sender = db.query(User).filter(User.id == req.from_user_id).first()
    logger.info(
        f"[FRIEND] accepted: request_id={request_id} "
        f"pair=({sender.nickname if sender else req.from_user_id!r} -> {user.nickname!r})"
    )
    return {"detail": "accepted", "friend_id": req.from_user_id}


@router.post("/friend-requests/{request_id}/reject")
def reject_friend_request(request_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    logger.info(f"[FRIEND] reject: request_id={request_id} by={user.id}")

    req = db.query(FriendRequest).filter(
        FriendRequest.id == request_id,
        FriendRequest.to_user_id == user.id,
        FriendRequest.status == "pending",
    ).first()
    if not req:
        raise HTTPException(status_code=404, detail="Friend request not found")

    req.status = "rejected"
    db.commit()
    logger.info(f"[FRIEND] rejected: request_id={request_id}")
    return {"detail": "rejected"}


# Friends List

@router.get("/friends")
def list_friends(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    logger.debug(f"[FRIEND] list friends for user={user.id}")
    friendships = db.query(Friendship).filter(Friendship.user_id == user.id).all()
    result = []
    for f in friendships:
        friend = db.query(User).filter(User.id == f.friend_id).first()
        if friend:
            online = manager.is_online(friend.id)
            result.append({"id": friend.id, "nickname": friend.nickname, "online": online})
    logger.info(
        f"[FRIEND] friends list: user={user.nickname!r} count={len(result)} "
        f"online={sum(1 for f in result if f['online'])}"
    )
    return result


@router.delete("/friends/{friend_id}", status_code=200)
def remove_friend(friend_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    logger.info(f"[FRIEND] remove: user={user.id} removing friend={friend_id}")

    f1 = db.query(Friendship).filter(Friendship.user_id == user.id, Friendship.friend_id == friend_id).first()
    f2 = db.query(Friendship).filter(Friendship.user_id == friend_id, Friendship.friend_id == user.id).first()

    if not f1 and not f2:
        raise HTTPException(status_code=404, detail="Friendship not found")

    if f1:
        db.delete(f1)
    if f2:
        db.delete(f2)
    db.commit()
    logger.info(f"[FRIEND] removed: pair=({user.id} -> {friend_id})")
    return {"detail": "removed"}


# User State (Phase 3 stub)

@router.get("/{user_id}/state")
def get_user_state(user_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    logger.debug(f"[USER] get_state: target={user_id} requester={user.id}")
    target = db.query(User).filter(User.id == user_id).first()
    if not target:
        raise HTTPException(status_code=404, detail="User not found")
    online = manager.is_online(target.id)
    # Phase 3 will populate room_id, track_id, position_ms etc.
    return {
        "user_id": target.id,
        "nickname": target.nickname,
        "online": online,
        "role": "SOLO",   # SOLO | HOST | LISTENER in phase 3
    }
