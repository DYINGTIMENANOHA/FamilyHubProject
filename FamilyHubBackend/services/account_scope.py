from sqlalchemy import func
from sqlalchemy.sql.elements import ColumnElement

from models import User


TEST_ACCOUNT_TYPE = "test"


def account_scope(account_or_type: User | str | None) -> str:
    """Return the isolation scope used by social and room features."""
    if isinstance(account_or_type, str) or account_or_type is None:
        account_type = account_or_type
    else:
        account_type = getattr(account_or_type, "account_type", None)
    return "test" if (account_type or "").strip().lower() == TEST_ACCOUNT_TYPE else "production"


def same_account_scope(left: User | str | None, right: User | str | None) -> bool:
    return account_scope(left) == account_scope(right)


def account_scope_filter(account_type_column, user: User) -> ColumnElement[bool]:
    """Build a SQLAlchemy condition matching users in the requester's scope."""
    normalized_type = func.lower(func.trim(account_type_column))
    if account_scope(user) == "test":
        return normalized_type == TEST_ACCOUNT_TYPE
    return normalized_type != TEST_ACCOUNT_TYPE
