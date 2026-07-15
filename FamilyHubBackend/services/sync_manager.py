import json
import logging
from fastapi import WebSocket

logger = logging.getLogger(__name__)


class SyncTuneConnectionManager:
    def __init__(self):
        # user_id -> WebSocket
        self._connections: dict[str, WebSocket] = {}
        logger.info("[WS][MANAGER] SyncTuneConnectionManager initialized")

    async def connect(self, user_id: str, ws: WebSocket, db) -> None:
        await ws.accept()
        self._connections[user_id] = ws
        logger.info(
            f"[WS][CONNECT] user_id={user_id} | total_online={len(self._connections)} "
            f"| online_list={list(self._connections.keys())}"
        )
        # 1. 告知所有好友"我上线了"
        await self._broadcast_presence(user_id, online=True, db=db)
        # 2. 把所有好友的当前在线状态发给刚连上的这个人（避免时序不一致）
        await self._send_presence_snapshot(user_id, db)

    async def disconnect(self, user_id: str, db) -> None:
        removed = self._connections.pop(user_id, None)
        if removed:
            logger.info(
                f"[WS][DISCONNECT] user_id={user_id} | total_online={len(self._connections)} "
                f"| online_list={list(self._connections.keys())}"
            )
        else:
            logger.warning(f"[WS][DISCONNECT] user_id={user_id} was not in connection map")
        await self._broadcast_presence(user_id, online=False, db=db)

    async def send_to(self, user_id: str, message: dict) -> bool:
        ws = self._connections.get(user_id)
        if ws is None:
            logger.debug(f"[WS][SEND] user_id={user_id} is offline, skipping")
            return False
        try:
            payload = json.dumps(message)
            await ws.send_text(payload)
            logger.debug(f"[WS][SEND] → user_id={user_id} payload={payload}")
            return True
        except Exception as e:
            logger.warning(f"[WS][SEND_FAIL] user_id={user_id} err={e!r}")
            self._connections.pop(user_id, None)
            return False

    def is_online(self, user_id: str) -> bool:
        online = user_id in self._connections
        logger.debug(f"[WS][IS_ONLINE] user_id={user_id} online={online}")
        return online

    def online_count(self) -> int:
        return len(self._connections)

    async def _send_presence_snapshot(self, user_id: str, db) -> None:
        """连接成功后，把所有好友的当前在线状态一次性推给刚上线的用户。"""
        friends = self._same_scope_friends(user_id, db)
        logger.debug(
            f"[WS][SNAPSHOT] sending presence snapshot to user_id={user_id} "
            f"friend_count={len(friends)}"
        )
        for f in friends:
            online = self.is_online(f.friend_id)
            await self.send_to(user_id, {
                "type": "PRESENCE_UPDATE",
                "user_id": f.friend_id,
                "online": online,
            })

    async def _broadcast_presence(self, user_id: str, online: bool, db) -> None:
        friends = self._same_scope_friends(user_id, db)
        msg = {"type": "PRESENCE_UPDATE", "user_id": user_id, "online": online}
        logger.debug(
            f"[WS][PRESENCE] broadcasting: user_id={user_id} online={online} "
            f"| friend_count={len(friends)}"
        )
        sent = 0
        failed = 0
        for f in friends:
            if await self.send_to(f.friend_id, msg):
                sent += 1
            else:
                failed += 1
        logger.info(
            f"[WS][PRESENCE] done: user_id={user_id} online={online} "
            f"| notified={sent}/{len(friends)} offline/failed={failed}"
        )

    @staticmethod
    def _same_scope_friends(user_id: str, db):
        from models import Friendship, User
        from services.account_scope import account_scope_filter

        user = db.query(User).filter(User.id == user_id).first()
        if user is None:
            return []
        return (
            db.query(Friendship)
            .join(User, User.id == Friendship.friend_id)
            .filter(
                Friendship.user_id == user_id,
                account_scope_filter(User.account_type, user),
            )
            .all()
        )


manager = SyncTuneConnectionManager()
