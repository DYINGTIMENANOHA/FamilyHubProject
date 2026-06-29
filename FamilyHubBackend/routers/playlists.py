import logging
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from auth import get_current_user
from database import get_db
from models import Playlist, PlaylistTrack, Track, User

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/playlists")


class PlaylistCreate(BaseModel):
    name: str


class PlaylistRename(BaseModel):
    name: str


class AddTrack(BaseModel):
    track_id: str


def _playlist_or_404(playlist_id: str, user_id: str, db: Session) -> Playlist:
    p = db.query(Playlist).filter(
        Playlist.id == playlist_id,
        Playlist.owner_id == user_id,
    ).first()
    if not p:
        raise HTTPException(status_code=404, detail="Playlist not found")
    return p


@router.get("")
def list_playlists(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    logger.debug(f"[PLAYLIST] list for user={user.id}")
    rows = db.query(Playlist).filter(Playlist.owner_id == user.id).order_by(Playlist.created_at.desc()).all()
    logger.info(f"[PLAYLIST] list: user={user.nickname} count={len(rows)}")
    return [{"id": p.id, "name": p.name, "created_at": p.created_at.isoformat()} for p in rows]


@router.post("", status_code=201)
def create_playlist(body: PlaylistCreate, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    logger.debug(f"[PLAYLIST] create: user={user.id} name={body.name!r}")
    p = Playlist(name=body.name, owner_id=user.id)
    db.add(p)
    db.commit()
    db.refresh(p)
    logger.info(f"[PLAYLIST] created: id={p.id} name={p.name!r} owner={user.nickname}")
    return {"id": p.id, "name": p.name, "created_at": p.created_at.isoformat()}


@router.get("/{playlist_id}")
def get_playlist(playlist_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    p = _playlist_or_404(playlist_id, user.id, db)
    pts = (
        db.query(PlaylistTrack)
        .filter(PlaylistTrack.playlist_id == playlist_id)
        .order_by(PlaylistTrack.position)
        .all()
    )
    tracks = []
    for pt in pts:
        t = db.query(Track).filter(Track.id == pt.track_id).first()
        if t:
            tracks.append({
                "id": t.id,
                "title": t.title,
                "artist": t.artist,
                "duration_ms": t.duration_ms,
                "position": pt.position,
            })
    logger.debug(f"[PLAYLIST] get: id={playlist_id} track_count={len(tracks)}")
    return {"id": p.id, "name": p.name, "tracks": tracks}


@router.patch("/{playlist_id}")
def rename_playlist(playlist_id: str, body: PlaylistRename, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    p = _playlist_or_404(playlist_id, user.id, db)
    old_name = p.name
    p.name = body.name
    db.commit()
    logger.info(f"[PLAYLIST] renamed: id={playlist_id} {old_name!r} → {body.name!r}")
    return {"id": p.id, "name": p.name}


@router.post("/{playlist_id}/tracks", status_code=201)
def add_track(playlist_id: str, body: AddTrack, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    _playlist_or_404(playlist_id, user.id, db)

    if not db.query(Track).filter(Track.id == body.track_id).first():
        raise HTTPException(status_code=404, detail="Track not found")

    if db.query(PlaylistTrack).filter(
        PlaylistTrack.playlist_id == playlist_id,
        PlaylistTrack.track_id == body.track_id,
    ).first():
        raise HTTPException(status_code=409, detail="Track already in playlist")

    position = db.query(PlaylistTrack).filter(PlaylistTrack.playlist_id == playlist_id).count()
    db.add(PlaylistTrack(playlist_id=playlist_id, track_id=body.track_id, position=position))
    db.commit()
    logger.info(f"[PLAYLIST] track added: playlist={playlist_id} track={body.track_id} pos={position}")
    return {"playlist_id": playlist_id, "track_id": body.track_id, "position": position}


@router.delete("/{playlist_id}/tracks/{track_id}", status_code=200)
def remove_track(playlist_id: str, track_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    _playlist_or_404(playlist_id, user.id, db)

    pt = db.query(PlaylistTrack).filter(
        PlaylistTrack.playlist_id == playlist_id,
        PlaylistTrack.track_id == track_id,
    ).first()
    if not pt:
        raise HTTPException(status_code=404, detail="Track not in playlist")

    removed_pos = pt.position
    db.delete(pt)

    for r in db.query(PlaylistTrack).filter(
        PlaylistTrack.playlist_id == playlist_id,
        PlaylistTrack.position > removed_pos,
    ).all():
        r.position -= 1

    db.commit()
    logger.info(f"[PLAYLIST] track removed: playlist={playlist_id} track={track_id}")
    return {"detail": "removed"}
