from config import API_TITLE, API_VERSION, LIVE_ROOM_CLEANUP_INTERVAL_SECONDS, setup_logging
setup_logging()  # must be first before imports that use logging

import asyncio
import logging
from fastapi import FastAPI
from database import init_db
from routers import tracks, playlists
from routers import users, websocket, live, integrations

logger = logging.getLogger(__name__)

app = FastAPI(title=API_TITLE, version=API_VERSION)


@app.on_event("startup")
async def on_startup():
    logger.info("[STARTUP] Initializing database...")
    init_db()
    from services.live_room_cleanup import cleanup_stale_live_rooms
    cleanup_stale_live_rooms()
    logger.info(f"[STARTUP] {API_TITLE} v{API_VERSION} ready")
    asyncio.create_task(_stale_room_cleanup_loop())


async def _stale_room_cleanup_loop():
    """Background task: dissolve stale sync rooms and expire stale live rooms."""
    while True:
        try:
            from services.room_manager import room_manager
            from services.sync_manager import manager
            from services.live_room_cleanup import cleanup_stale_live_rooms
            dissolved = room_manager.cleanup_stale_rooms()
            ended_live_room_ids = cleanup_stale_live_rooms()
            if dissolved:
                logger.info(f"[CLEANUP] {len(dissolved)} stale room(s) dissolved")
            if ended_live_room_ids:
                logger.info(f"[CLEANUP] ended stale live rooms: {ended_live_room_ids}")
            for room_id, members in dissolved:
                for mid in members:
                    await manager.send_to(mid, {
                        "type": "ROOM_DISSOLVED",
                        "room_id": room_id,
                        "reason": "inactivity",
                    })
        except Exception as e:
            logger.exception(f"[CLEANUP] error: {e!r}")
        await asyncio.sleep(max(1, LIVE_ROOM_CLEANUP_INTERVAL_SECONDS))


app.include_router(tracks.router)
app.include_router(playlists.router)
app.include_router(users.router)
app.include_router(websocket.router)
app.include_router(live.router)
app.include_router(integrations.router)


@app.get("/health")
def health():
    return {
        "status": "ok",
        "version": API_VERSION,
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
