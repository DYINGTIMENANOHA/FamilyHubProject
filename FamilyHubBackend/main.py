from config import API_TITLE, API_VERSION, setup_logging
setup_logging()  # must be first 鈥?before any other imports that use logging

import asyncio
import logging
from fastapi import FastAPI
from database import init_db
from routers import tracks, playlists
from routers import users, websocket

logger = logging.getLogger(__name__)

app = FastAPI(title=API_TITLE, version=API_VERSION)


@app.on_event("startup")
async def on_startup():
    logger.info("[STARTUP] Initializing database...")
    init_db()
    logger.info(f"[STARTUP] {API_TITLE} v{API_VERSION} ready")
    asyncio.create_task(_stale_room_cleanup_loop())


async def _stale_room_cleanup_loop():
    """Background task: dissolve rooms inactive for more than 1 hour."""
    while True:
        await asyncio.sleep(300)  # check every 5 minutes
        try:
            from services.room_manager import room_manager
            from services.sync_manager import manager
            dissolved = room_manager.cleanup_stale_rooms()
            if dissolved:
                logger.info(f"[CLEANUP] {len(dissolved)} stale room(s) dissolved")
            for room_id, members in dissolved:
                for mid in members:
                    await manager.send_to(mid, {
                        "type": "ROOM_DISSOLVED",
                        "room_id": room_id,
                        "reason": "inactivity",
                    })
        except Exception as e:
            logger.exception(f"[CLEANUP] error: {e!r}")


app.include_router(tracks.router)
app.include_router(playlists.router)
app.include_router(users.router)
app.include_router(websocket.router)


@app.get("/health")
def health():
    from services.sync_manager import manager
    from services.room_manager import room_manager
    return {
        "status": "ok",
        "version": API_VERSION,
        "online_users": manager.online_count(),
        "active_rooms": room_manager.rooms_info(),
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
