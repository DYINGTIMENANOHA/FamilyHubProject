import uuid
from datetime import datetime
from sqlalchemy import Boolean, Column, String, Integer, DateTime, ForeignKey, UniqueConstraint
from database import Base


class Track(Base):
    __tablename__ = "tracks"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    title = Column(String, nullable=False)
    artist = Column(String)
    duration_ms = Column(Integer)
    file_path = Column(String, nullable=False)
    cover_path = Column(String)
    source = Column(String, default="upload")
    uploaded_by = Column(String)
    created_at = Column(DateTime, default=datetime.utcnow)


class User(Base):
    __tablename__ = "users"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    nickname = Column(String, nullable=False, unique=True)
    password_hash = Column(String, nullable=True)
    token = Column(String, unique=True, nullable=False)
    status = Column(String, nullable=False, default="active")
    max_devices = Column(Integer, nullable=False, default=2)
    disabled_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)


class UserDevice(Base):
    __tablename__ = "user_devices"
    __table_args__ = (UniqueConstraint("user_id", "device_id", name="uq_user_device"),)

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id = Column(String, ForeignKey("users.id"), nullable=False)
    device_id = Column(String, nullable=False)
    device_name = Column(String, nullable=True)
    platform = Column(String, nullable=True)
    active = Column(Boolean, nullable=False, default=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    last_seen_at = Column(DateTime, default=datetime.utcnow)


class AccountSession(Base):
    __tablename__ = "account_sessions"

    token = Column(String, primary_key=True)
    user_id = Column(String, ForeignKey("users.id"), nullable=False)
    device_id = Column(String, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)
    last_seen_at = Column(DateTime, default=datetime.utcnow)
    revoked_at = Column(DateTime, nullable=True)


class Playlist(Base):
    __tablename__ = "playlists"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    name = Column(String, nullable=False)
    owner_id = Column(String, ForeignKey("users.id"), nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)


class PlaylistTrack(Base):
    __tablename__ = "playlist_tracks"

    playlist_id = Column(String, ForeignKey("playlists.id"), primary_key=True)
    track_id = Column(String, ForeignKey("tracks.id"), primary_key=True)
    position = Column(Integer, nullable=False)


class FriendRequest(Base):
    __tablename__ = "friend_requests"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    from_user_id = Column(String, ForeignKey("users.id"), nullable=False)
    to_user_id = Column(String, ForeignKey("users.id"), nullable=False)
    status = Column(String, nullable=False, default="pending")
    created_at = Column(DateTime, default=datetime.utcnow)


class Friendship(Base):
    __tablename__ = "friendships"

    user_id = Column(String, ForeignKey("users.id"), primary_key=True)
    friend_id = Column(String, ForeignKey("users.id"), primary_key=True)
    created_at = Column(DateTime, default=datetime.utcnow)
class LiveRoom(Base):
    __tablename__ = "live_rooms"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    title = Column(String, nullable=False)
    owner_id = Column(String, ForeignKey("users.id"), nullable=False)
    status = Column(String, nullable=False, default="live")
    source_type = Column(String, nullable=False, default="camera")
    quality = Column(String, nullable=False, default="original")
    livekit_room_name = Column(String, nullable=False, unique=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    started_at = Column(DateTime, default=datetime.utcnow)
    heartbeat_at = Column(DateTime, nullable=True)
    ended_at = Column(DateTime, nullable=True)
