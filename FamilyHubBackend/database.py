import logging
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker, DeclarativeBase
from config import DATABASE_URL

logger = logging.getLogger(__name__)

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


class Base(DeclarativeBase):
    pass


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def _add_column_if_missing(conn, table: str, column: str, ddl: str) -> None:
    result = conn.execute(text(f"PRAGMA table_info({table})"))
    columns = {row[1] for row in result}
    if column not in columns:
        conn.execute(text(f"ALTER TABLE {table} ADD COLUMN {ddl}"))
        conn.commit()
        logger.info(f"[MIGRATE] Added column {table}.{column}")


def _migrate() -> None:
    with engine.connect() as conn:
        _add_column_if_missing(conn, "users", "password_hash", "password_hash TEXT")
        _add_column_if_missing(conn, "users", "status", "status TEXT NOT NULL DEFAULT 'active'")
        _add_column_if_missing(conn, "users", "max_devices", "max_devices INTEGER NOT NULL DEFAULT 2")
        _add_column_if_missing(conn, "users", "disabled_at", "disabled_at DATETIME")

        dup = conn.execute(
            text("SELECT nickname, COUNT(*) c FROM users GROUP BY nickname HAVING c > 1")
        ).fetchall()
        if dup:
            logger.warning(f"[MIGRATE] Duplicate nicknames found (may break login): {dup}")


def init_db() -> None:
    import models  # noqa: F401 registers ORM models before create_all
    Base.metadata.create_all(bind=engine)
    logger.info("[DB] create_all done")
    _migrate()