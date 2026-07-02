import mimetypes
import re
import uuid
from pathlib import Path

import aiofiles
from fastapi import APIRouter, Depends, HTTPException, Request, UploadFile, File
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session

from config import UPLOADS_DIR
from auth import get_current_user
from database import get_db
from models import Track, User

router = APIRouter()

ALLOWED_EXTENSIONS = {".mp3", ".flac", ".wav", ".m4a", ".ogg", ".aac"}
CHUNK_SIZE = 64 * 1024  # 64 KB


def _extract_metadata(file_path: Path, original_filename: str):
    title = Path(original_filename).stem
    artist = None
    duration_ms = None
    try:
        from mutagen import File as MutagenFile
        audio = MutagenFile(file_path, easy=True)
        if audio:
            if hasattr(audio, "info") and hasattr(audio.info, "length"):
                duration_ms = int(audio.info.length * 1000)
            if audio.tags:
                title = audio.tags.get("title", [title])[0]
                artist = audio.tags.get("artist", [None])[0]
    except Exception:
        pass
    return title, artist, duration_ms


@router.post("/upload")
async def upload_track(
    file: UploadFile = File(...),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    suffix = Path(file.filename).suffix.lower()
    if suffix not in ALLOWED_EXTENSIONS:
        raise HTTPException(status_code=400, detail=f"Unsupported file type: {suffix}")

    track_id = str(uuid.uuid4())
    dest = UPLOADS_DIR / f"{track_id}{suffix}"

    async with aiofiles.open(dest, "wb") as f:
        while chunk := await file.read(1024 * 1024):
            await f.write(chunk)

    title, artist, duration_ms = _extract_metadata(dest, file.filename)

    track = Track(
        id=track_id,
        title=title,
        artist=artist,
        duration_ms=duration_ms,
        file_path=str(dest),
        source="upload",
        uploaded_by=user.id,
    )
    db.add(track)
    db.commit()
    db.refresh(track)

    return {
        "id": track.id,
        "title": track.title,
        "artist": track.artist,
        "duration_ms": track.duration_ms,
    }


@router.get("/tracks")
def get_tracks(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    tracks = db.query(Track).order_by(Track.created_at.desc()).all()
    return [
        {
            "id": t.id,
            "title": t.title,
            "artist": t.artist,
            "duration_ms": t.duration_ms,
            "source": t.source,
            "created_at": t.created_at.isoformat() if t.created_at else None,
        }
        for t in tracks
    ]


@router.get("/tracks/{track_id}")
def get_track(
    track_id: str,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    track = db.query(Track).filter(Track.id == track_id).first()
    if not track:
        raise HTTPException(status_code=404, detail="Track not found")
    return {
        "id": track.id,
        "title": track.title,
        "artist": track.artist,
        "duration_ms": track.duration_ms,
        "source": track.source,
        "created_at": track.created_at.isoformat() if track.created_at else None,
    }


@router.get("/stream/{track_id}")
async def stream_track(
    track_id: str,
    request: Request,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    track = db.query(Track).filter(Track.id == track_id).first()
    if not track:
        raise HTTPException(status_code=404, detail="Track not found")

    file_path = Path(track.file_path)
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="Audio file not found on disk")

    file_size = file_path.stat().st_size
    mime_type, _ = mimetypes.guess_type(str(file_path))
    mime_type = mime_type or "application/octet-stream"

    range_header = request.headers.get("range")
    if range_header:
        match = re.match(r"bytes=(\d+)-(\d*)", range_header)
        if match:
            start = int(match.group(1))
            end = int(match.group(2)) if match.group(2) else file_size - 1
            end = min(end, file_size - 1)
            length = end - start + 1

            async def ranged_body():
                async with aiofiles.open(file_path, "rb") as f:
                    await f.seek(start)
                    remaining = length
                    while remaining > 0:
                        chunk = await f.read(min(CHUNK_SIZE, remaining))
                        if not chunk:
                            break
                        remaining -= len(chunk)
                        yield chunk

            return StreamingResponse(
                ranged_body(),
                status_code=206,
                media_type=mime_type,
                headers={
                    "Content-Range": f"bytes {start}-{end}/{file_size}",
                    "Accept-Ranges": "bytes",
                    "Content-Length": str(length),
                },
            )

    async def full_body():
        async with aiofiles.open(file_path, "rb") as f:
            while chunk := await f.read(CHUNK_SIZE):
                yield chunk

    return StreamingResponse(
        full_body(),
        media_type=mime_type,
        headers={
            "Accept-Ranges": "bytes",
            "Content-Length": str(file_size),
        },
    )
