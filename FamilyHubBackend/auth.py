import logging
from datetime import datetime
from fastapi import Header, HTTPException, Depends
from sqlalchemy.orm import Session
from database import get_db
from models import AccountSession, User

logger = logging.getLogger(__name__)


def authenticate_token(db: Session, token: str) -> User | None:
    logger.debug(f"[AUTH] token lookup: {token[:8]}...")

    session = db.query(AccountSession).filter(
        AccountSession.token == token,
        AccountSession.revoked_at.is_(None),
    ).first()
    if session:
        user = db.query(User).filter(User.id == session.user_id).first()
        if not user or user.status != "active":
            logger.warning(f"[AUTH] rejected inactive session user_id={session.user_id}")
            return None
        session.last_seen_at = datetime.utcnow()
        db.commit()
        logger.debug(f"[AUTH] session authenticated: user_id={user.id} nickname={user.nickname}")
        return user

    user = db.query(User).filter(User.token == token).first()
    if not user or user.status != "active":
        logger.warning(f"[AUTH] invalid token: {token[:8]}...")
        return None
    logger.debug(f"[AUTH] legacy token authenticated: user_id={user.id} nickname={user.nickname}")
    return user


def _reject() -> None:
    raise HTTPException(status_code=401, detail="Invalid or expired X-Token header")


def get_current_user(
    x_token: str = Header(alias="X-Token"),
    db: Session = Depends(get_db),
) -> User:
    user = authenticate_token(db, x_token)
    if not user:
        _reject()
    return user