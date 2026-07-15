import json
import logging
import time

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect

from auth import authenticate_token
from database import SessionLocal
from models import User
from services.sync_manager import manager
from services.room_manager import room_manager
from services.account_scope import account_scope, same_account_scope

logger = logging.getLogger(__name__)
router = APIRouter()


# Permission helper
def _can_send_sync(room, user_id: str) -> bool:
    return user_id == room.host_id or room.allow_guest_control


# Message dispatcher
async def _handle(msg: dict, user: User, db) -> None:
    uid = user.id
    mtype = msg.get("type", "")

    # ROOM_JOIN
    if mtype == "ROOM_JOIN":
        host_id = msg.get("host_id")
        if not host_id:
            await manager.send_to(uid, {"type": "ERROR", "detail": "missing host_id"})
            return
        if host_id == uid:
            await manager.send_to(uid, {"type": "ERROR", "detail": "cannot_follow_self"})
            return

        host_user = db.query(User).filter(User.id == host_id).first()
        if host_user is None or not same_account_scope(user, host_user):
            await manager.send_to(uid, {"type": "ERROR", "detail": "room_unavailable"})
            return
        if not manager.is_online(host_id):
            await manager.send_to(uid, {"type": "ERROR", "detail": "host_offline"})
            return

        room = room_manager.get_room_by_host(host_id)
        if room is None:
            room = room_manager.create_room(host_id, account_scope(host_user))
            logger.info(f"[WS][ROOM_JOIN] auto-created room for host={host_id}")
            await manager.send_to(host_id, {
                "type": "ROOM_STATE",
                "role": "HOSTING",
                "room_id": room.id,
                "allow_guest_control": room.allow_guest_control,
                "members": list(room.member_ids),
            })

        room = room_manager.join_room(host_id, uid, account_scope(user))
        if room is None:
            await manager.send_to(uid, {"type": "ERROR", "detail": "join_failed"})
            return

        host_nickname = host_user.nickname if host_user else host_id

        await manager.send_to(uid, {
            "type": "ROOM_STATE",
            "role": "FOLLOWING",
            "room_id": room.id,
            "host_id": host_id,
            "host_nickname": host_nickname,
            "track_id": room.track_id,
            "position_ms": room.position_ms,
            "is_playing": room.is_playing,
            "timestamp": int(time.time() * 1000),
            "allow_guest_control": room.allow_guest_control,
        })

        for mid in room.member_ids:
            if mid != uid:
                await manager.send_to(mid, {
                    "type": "ROOM_MEMBER_UPDATE",
                    "room_id": room.id,
                    "members": list(room.member_ids),
                    "joined": uid,
                })
        logger.info(
            f"[WS][ROOM_JOIN] follower={user.nickname!r} -> "
            f"host={host_nickname!r} room={room.id}"
        )

    # ROOM_LEAVE
    elif mtype == "ROOM_LEAVE":
        room, dissolved, affected = room_manager.leave_room(uid)
        if room is None:
            await manager.send_to(uid, {"type": "ACK", "detail": "not_in_room"})
            return
        if dissolved:
            for mid in affected:
                if mid != uid:
                    await manager.send_to(mid, {
                        "type": "ROOM_DISSOLVED",
                        "room_id": room.id,
                        "reason": "host_left",
                    })
            await manager.send_to(uid, {"type": "ACK", "detail": "room_dissolved"})
            logger.info(f"[WS][ROOM_LEAVE] host={user.nickname!r} dissolved room={room.id}")
        else:
            await manager.send_to(uid, {"type": "ACK", "detail": "room_left"})
            for mid in affected:
                await manager.send_to(mid, {
                    "type": "ROOM_MEMBER_UPDATE",
                    "room_id": room.id,
                    "members": list(affected),
                    "left": uid,
                })
            logger.info(f"[WS][ROOM_LEAVE] follower={user.nickname!r} left room={room.id}")

    # ROOM_SETTINGS
    elif mtype == "ROOM_SETTINGS":
        room = room_manager.get_room_by_user(uid)
        if room is None or room.host_id != uid:
            await manager.send_to(uid, {"type": "ERROR", "detail": "not_host"})
            return
        allow = bool(msg.get("allow_guest_control", False))
        room_manager.set_guest_control(room.id, allow)
        for mid in room.member_ids:
            await manager.send_to(mid, {
                "type": "ROOM_SETTINGS",
                "room_id": room.id,
                "allow_guest_control": allow,
            })
        logger.info(f"[WS][ROOM_SETTINGS] host={user.nickname!r} allow_guest_control={allow}")

    # ROOM_CATCHUP
    elif mtype == "ROOM_CATCHUP":
        room = room_manager.get_room_by_user(uid)
        if room is None:
            await manager.send_to(uid, {"type": "ERROR", "detail": "not_in_room"})
            return
        if room.track_id is None:
            await manager.send_to(uid, {"type": "ACK", "detail": "no_track_yet"})
            logger.info(f"[WS][ROOM_CATCHUP] room has no track yet, told follower={user.nickname!r} to wait")
            return
        await manager.send_to(uid, {
            "type": "SYNC_FORCE",
            "track_id": room.track_id,
            "position_ms": room.position_ms,
            "is_playing": room.is_playing,
            "timestamp": int(time.time() * 1000),
            "sender_id": room.host_id,
        })
        logger.info(
            f"[WS][ROOM_CATCHUP] sent SYNC_FORCE to follower={user.nickname!r} "
            f"track={room.track_id} pos={room.position_ms}"
        )

    # SYNC_ALL
    elif mtype == "SYNC_ALL":
        room = room_manager.get_room_by_user(uid)
        if room is None:
            await manager.send_to(uid, {"type": "ERROR", "detail": "not_in_room"})
            return
        if not _can_send_sync(room, uid):
            await manager.send_to(uid, {"type": "ERROR", "detail": "not_allowed"})
            return

        track_id = msg.get("track_id")
        position_ms = int(msg.get("position_ms", 0))
        is_playing = bool(msg.get("is_playing", False))
        timestamp = int(msg.get("timestamp", time.time() * 1000))

        room_manager.update_state(
            room.id, track_id=track_id,
            position_ms=position_ms, is_playing=is_playing,
        )
        broadcast = {
            "type": "SYNC_FORCE",
            "track_id": track_id,
            "position_ms": position_ms,
            "is_playing": is_playing,
            "timestamp": timestamp,
            "sender_id": uid,
        }
        sent = 0
        for mid in room.member_ids:
            if mid != uid:
                if await manager.send_to(mid, broadcast):
                    sent += 1
        logger.info(
            f"[WS][SYNC_ALL] from={user.nickname!r} track={track_id} "
            f"pos={position_ms} playing={is_playing} notified={sent}"
        )

    # SYNC_PLAY / SYNC_PAUSE / SYNC_SEEK
    elif mtype in ("SYNC_PLAY", "SYNC_PAUSE", "SYNC_SEEK"):
        room = room_manager.get_room_by_user(uid)
        if room is None:
            await manager.send_to(uid, {"type": "ERROR", "detail": "not_in_room"})
            return
        if not _can_send_sync(room, uid):
            await manager.send_to(uid, {"type": "ERROR", "detail": "not_allowed"})
            return

        timestamp = int(msg.get("timestamp", time.time() * 1000))
        if mtype == "SYNC_PLAY":
            room_manager.update_state(room.id, is_playing=True)
        elif mtype == "SYNC_PAUSE":
            room_manager.update_state(room.id, is_playing=False)
        elif mtype == "SYNC_SEEK":
            room_manager.update_state(room.id, position_ms=int(msg.get("position_ms", 0)))

        broadcast = dict(msg)
        broadcast["sender_id"] = uid
        broadcast["timestamp"] = timestamp
        for mid in room.member_ids:
            if mid != uid:
                await manager.send_to(mid, broadcast)
        logger.info(f"[WS][{mtype}] from={user.nickname!r} room={room.id}")

    else:
        logger.debug(f"[WS][UNKNOWN] type={mtype!r} from={user.nickname!r}")
        await manager.send_to(uid, {"type": "ACK", "echo": msg})


# WebSocket endpoint
@router.websocket("/ws")
async def websocket_endpoint(ws: WebSocket, token: str = Query(...)):
    db = SessionLocal()
    try:
        user = authenticate_token(db, token)
        if not user:
            logger.warning(f"[WS][AUTH] rejected token={token[:8]}...")
            await ws.close(code=4001, reason="Invalid token")
            return

        logger.info(f"[WS][AUTH] accepted user_id={user.id} nick={user.nickname!r}")

        room_manager.cancel_host_dissolve(user.id)
        await manager.connect(user.id, ws, db)

        try:
            while True:
                raw = await ws.receive_text()
                logger.debug(f"[WS][MSG] from={user.nickname!r} raw={raw}")
                try:
                    msg = json.loads(raw)
                    logger.info(f"[WS][MSG] type={msg.get('type','?')!r} from={user.nickname!r}")
                    await _handle(msg, user, db)
                except json.JSONDecodeError:
                    logger.warning(f"[WS][MSG] non-JSON from={user.nickname!r}")
                    await manager.send_to(user.id, {"type": "ERROR", "detail": "invalid_json"})

        except WebSocketDisconnect as e:
            logger.info(f"[WS][DISCONNECT] nick={user.nickname!r} code={e.code}")
            await manager.disconnect(user.id, db)

            room = room_manager.get_room_by_host(user.id)
            if room:
                logger.info(
                    f"[WS][HOST_DISCONNECT] scheduling dissolve "
                    f"room={room.id} host={user.nickname!r}"
                )

                async def _on_host_gone(host_id: str):
                    r = room_manager.get_room_by_host(host_id)
                    if r is None:
                        return
                    _, members = room_manager.dissolve_room_by_id(r.id)
                    for mid in members:
                        if mid != host_id:
                            await manager.send_to(mid, {
                                "type": "ROOM_DISSOLVED",
                                "room_id": r.id,
                                "reason": "host_disconnected",
                            })
                    logger.info(
                        f"[WS][DISSOLVE] room={r.id} dissolved "
                        f"after grace period host={host_id}"
                    )

                room_manager.schedule_host_dissolve(user.id, _on_host_gone)

    except Exception as e:
        logger.exception(f"[WS][ERROR] unexpected: {e!r}")
    finally:
        db.close()
