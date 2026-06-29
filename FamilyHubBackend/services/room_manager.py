import asyncio
import logging
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Optional

logger = logging.getLogger(__name__)

HOST_DISCONNECT_GRACE_SECS = 30
STALE_ROOM_HOURS = 1


@dataclass
class Room:
    id: str
    host_id: str
    track_id: Optional[str] = None
    position_ms: int = 0
    is_playing: bool = False
    allow_guest_control: bool = False
    last_activity: datetime = field(default_factory=datetime.utcnow)
    member_ids: set = field(default_factory=set)  # includes host


class RoomManager:
    def __init__(self):
        self._rooms: dict[str, Room] = {}           # room_id -> Room
        self._user_room: dict[str, str] = {}         # user_id -> room_id
        self._dissolve_tasks: dict[str, asyncio.Task] = {}  # host_id -> pending task
        logger.info("[ROOM] RoomManager initialized")

    # ── Room creation ──────────────────────────────────────────────────────────

    def create_room(self, host_id: str) -> Room:
        self._force_leave(host_id)
        room = Room(id=str(uuid.uuid4()), host_id=host_id, member_ids={host_id})
        self._rooms[room.id] = room
        self._user_room[host_id] = room.id
        logger.info(f"[ROOM] created room_id={room.id} host={host_id}")
        return room

    # ── Join / Leave ───────────────────────────────────────────────────────────

    def join_room(self, host_id: str, follower_id: str) -> Optional[Room]:
        if host_id == follower_id:
            logger.warning(f"[ROOM] join_room: cannot follow yourself host={host_id}")
            return None
        self._force_leave(follower_id)
        room = self.get_room_by_host(host_id)
        if room is None:
            logger.warning(f"[ROOM] join_room: no room for host={host_id}")
            return None
        room.member_ids.add(follower_id)
        self._user_room[follower_id] = room.id
        room.last_activity = datetime.utcnow()
        logger.info(
            f"[ROOM] joined room_id={room.id} host={host_id} "
            f"follower={follower_id} members={room.member_ids}"
        )
        return room

    def leave_room(self, user_id: str) -> tuple:
        """Returns (room, was_dissolved, affected_member_ids)."""
        room_id = self._user_room.get(user_id)
        if room_id is None:
            return None, False, set()
        room = self._rooms.get(room_id)
        if room is None:
            self._user_room.pop(user_id, None)
            return None, False, set()

        if user_id == room.host_id:
            members = set(room.member_ids)
            for mid in members:
                self._user_room.pop(mid, None)
            del self._rooms[room_id]
            logger.info(f"[ROOM] dissolved by host leave room_id={room_id} members={members}")
            return room, True, members
        else:
            room.member_ids.discard(user_id)
            self._user_room.pop(user_id, None)
            logger.info(
                f"[ROOM] follower left room_id={room_id} "
                f"user={user_id} remaining={room.member_ids}"
            )
            return room, False, set(room.member_ids)

    def _force_leave(self, user_id: str) -> None:
        room_id = self._user_room.get(user_id)
        if room_id is None:
            return
        room = self._rooms.get(room_id)
        if room is None:
            self._user_room.pop(user_id, None)
            return
        if user_id == room.host_id:
            for mid in room.member_ids:
                self._user_room.pop(mid, None)
            del self._rooms[room_id]
            logger.debug(f"[ROOM] force-dissolved room_id={room_id}")
        else:
            room.member_ids.discard(user_id)
            self._user_room.pop(user_id, None)

    # ── State updates ──────────────────────────────────────────────────────────

    def update_state(
        self,
        room_id: str,
        track_id=None,
        position_ms: Optional[int] = None,
        is_playing: Optional[bool] = None,
    ) -> Optional[Room]:
        room = self._rooms.get(room_id)
        if room is None:
            return None
        if track_id is not None:
            room.track_id = track_id
        if position_ms is not None:
            room.position_ms = position_ms
        if is_playing is not None:
            room.is_playing = is_playing
        room.last_activity = datetime.utcnow()
        logger.debug(
            f"[ROOM] state updated room_id={room_id} "
            f"track={room.track_id} pos={room.position_ms} playing={room.is_playing}"
        )
        return room

    def set_guest_control(self, room_id: str, allow: bool) -> Optional[Room]:
        room = self._rooms.get(room_id)
        if room:
            room.allow_guest_control = allow
            room.last_activity = datetime.utcnow()
            logger.info(f"[ROOM] guest_control={allow} room_id={room_id}")
        return room

    # ── Lookups ────────────────────────────────────────────────────────────────

    def get_room_by_user(self, user_id: str) -> Optional[Room]:
        room_id = self._user_room.get(user_id)
        return self._rooms.get(room_id) if room_id else None

    def get_room_by_host(self, host_id: str) -> Optional[Room]:
        room_id = self._user_room.get(host_id)
        if room_id is None:
            return None
        room = self._rooms.get(room_id)
        if room and room.host_id == host_id:
            return room
        return None

    # ── Dissolve ───────────────────────────────────────────────────────────────

    def dissolve_room_by_id(self, room_id: str) -> tuple:
        """Returns (room, member_ids). Removes room from all indexes."""
        room = self._rooms.pop(room_id, None)
        if room is None:
            return None, set()
        members = set(room.member_ids)
        for mid in members:
            self._user_room.pop(mid, None)
        logger.info(f"[ROOM] dissolved room_id={room_id} members={members}")
        return room, members

    # ── Grace-period dissolve (host disconnect) ────────────────────────────────

    def schedule_host_dissolve(self, host_id: str, callback) -> None:
        existing = self._dissolve_tasks.pop(host_id, None)
        if existing and not existing.done():
            existing.cancel()

        async def _delayed():
            await asyncio.sleep(HOST_DISCONNECT_GRACE_SECS)
            self._dissolve_tasks.pop(host_id, None)
            logger.info(f"[ROOM] grace period expired for host={host_id}, dissolving")
            await callback(host_id)

        self._dissolve_tasks[host_id] = asyncio.create_task(_delayed())
        logger.info(
            f"[ROOM] scheduled dissolve in {HOST_DISCONNECT_GRACE_SECS}s "
            f"for host={host_id}"
        )

    def cancel_host_dissolve(self, host_id: str) -> bool:
        task = self._dissolve_tasks.pop(host_id, None)
        if task and not task.done():
            task.cancel()
            logger.info(f"[ROOM] cancelled pending dissolve for host={host_id}")
            return True
        return False

    # ── Stale room cleanup ─────────────────────────────────────────────────────

    def cleanup_stale_rooms(self) -> list:
        """Returns list of (room_id, member_ids) for each dissolved room."""
        cutoff = datetime.utcnow() - timedelta(hours=STALE_ROOM_HOURS)
        stale = [
            (rid, r) for rid, r in list(self._rooms.items())
            if r.last_activity < cutoff
        ]
        results = []
        for rid, _ in stale:
            _, members = self.dissolve_room_by_id(rid)
            results.append((rid, members))
            logger.info(f"[ROOM][CLEANUP] stale room dissolved room_id={rid}")
        return results

    # ── Debug info ─────────────────────────────────────────────────────────────

    def rooms_info(self) -> list[dict]:
        return [
            {
                "id": r.id,
                "host_id": r.host_id,
                "track_id": r.track_id,
                "member_count": len(r.member_ids),
                "is_playing": r.is_playing,
                "allow_guest_control": r.allow_guest_control,
                "last_activity": r.last_activity.isoformat(),
            }
            for r in self._rooms.values()
        ]


room_manager = RoomManager()
