import argparse
import json
import logging
import secrets
import uuid
from datetime import datetime

logging.getLogger("passlib.handlers.bcrypt").setLevel(logging.ERROR)

from passlib.context import CryptContext

from config import DEFAULT_MAX_DEVICES
from database import SessionLocal, init_db
from models import AccountSession, Friendship, FriendRequest, User, UserDevice

_pwd_ctx = CryptContext(schemes=["bcrypt"], deprecated="auto")


def _hash_password(password: str) -> str:
    return _pwd_ctx.hash(password)


def _user(db, nickname: str) -> User:
    user = db.query(User).filter(User.nickname == nickname).first()
    if not user:
        raise SystemExit(f"account not found: {nickname}")
    return user


def _resource_params(user: User) -> dict:
    raw = (getattr(user, "resource_params", None) or "{}").strip()
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _set_resource_params(user: User, params: dict) -> None:
    user.resource_params = json.dumps(params, sort_keys=True, separators=(",", ":"))


def _initial_resource_params(args) -> str:
    params = {}
    if args.resource_json:
        try:
            parsed = json.loads(args.resource_json)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"invalid --resource-json: {exc}") from exc
        if not isinstance(parsed, dict):
            raise SystemExit("--resource-json must be a JSON object")
        params.update(parsed)

    if args.livestream_env:
        params.setdefault("livestream", {})["env"] = args.livestream_env

    return json.dumps(params, sort_keys=True, separators=(",", ":"))


def account_add(args):
    init_db()
    db = SessionLocal()
    try:
        existing = db.query(User).filter(User.nickname == args.nickname).first()
        if existing:
            raise SystemExit(f"account already exists: {args.nickname}")
        password = args.password or secrets.token_urlsafe(12)
        user = User(
            id=str(uuid.uuid4()),
            nickname=args.nickname,
            password_hash=_hash_password(password),
            token=secrets.token_hex(32),
            status="active",
            account_type=args.account_type,
            resource_params=_initial_resource_params(args),
            max_devices=args.max_devices,
        )
        db.add(user)
        db.commit()
        print(f"created account: {user.nickname}")
        print(f"user_id: {user.id}")
        print(f"account_type: {user.account_type}")
        print(f"resource_params: {user.resource_params}")
        print(f"max_devices: {user.max_devices}")
        if args.password:
            print("password: <provided>")
        else:
            print(f"password: {password}")
    finally:
        db.close()


def account_list(args):
    init_db()
    db = SessionLocal()
    try:
        users = db.query(User).order_by(User.created_at.desc()).all()
        if not users:
            print("no accounts")
            return
        for user in users:
            active_devices = db.query(UserDevice).filter(
                UserDevice.user_id == user.id,
                UserDevice.active.is_(True),
            ).count()
            print(
                f"{user.nickname}\tstatus={user.status}\ttype={user.account_type}\t"
                f"devices={active_devices}/{user.max_devices}\tresources={user.resource_params}\tcreated={user.created_at}"
            )
    finally:
        db.close()


def account_delete(args):
    init_db()
    db = SessionLocal()
    try:
        user = _user(db, args.nickname)
        db.query(AccountSession).filter(AccountSession.user_id == user.id).delete()
        db.query(UserDevice).filter(UserDevice.user_id == user.id).delete()
        db.query(FriendRequest).filter(FriendRequest.from_user_id == user.id).delete()
        db.query(FriendRequest).filter(FriendRequest.to_user_id == user.id).delete()
        db.query(Friendship).filter(Friendship.user_id == user.id).delete()
        db.query(Friendship).filter(Friendship.friend_id == user.id).delete()
        db.delete(user)
        db.commit()
        print(f"deleted account: {args.nickname}")
    finally:
        db.close()


def account_status(args, status: str):
    init_db()
    db = SessionLocal()
    try:
        user = _user(db, args.nickname)
        user.status = status
        user.disabled_at = datetime.utcnow() if status != "active" else None
        if status != "active":
            db.query(AccountSession).filter(
                AccountSession.user_id == user.id,
                AccountSession.revoked_at.is_(None),
            ).update({"revoked_at": datetime.utcnow()})
        db.commit()
        print(f"updated account: {user.nickname} status={user.status}")
    finally:
        db.close()


def account_set_max_devices(args):
    init_db()
    db = SessionLocal()
    try:
        user = _user(db, args.nickname)
        user.max_devices = args.max_devices
        db.commit()
        print(f"updated account: {user.nickname} max_devices={user.max_devices}")
    finally:
        db.close()


def account_set_type(args):
    init_db()
    db = SessionLocal()
    try:
        user = _user(db, args.nickname)
        user.account_type = args.account_type
        db.commit()
        print(f"updated account: {user.nickname} account_type={user.account_type}")
    finally:
        db.close()


def account_set_livestream_env(args):
    init_db()
    db = SessionLocal()
    try:
        user = _user(db, args.nickname)
        params = _resource_params(user)
        livestream = params.setdefault("livestream", {})
        if args.env == "default":
            livestream.pop("env", None)
            if not livestream:
                params.pop("livestream", None)
        else:
            livestream["env"] = args.env
        _set_resource_params(user, params)
        db.commit()
        print(f"updated account: {user.nickname} resource_params={user.resource_params}")
    finally:
        db.close()


def account_set_resource_json(args):
    init_db()
    db = SessionLocal()
    try:
        user = _user(db, args.nickname)
        try:
            params = json.loads(args.resource_json)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"invalid resource_json: {exc}") from exc
        if not isinstance(params, dict):
            raise SystemExit("resource_json must be a JSON object")
        _set_resource_params(user, params)
        db.commit()
        print(f"updated account: {user.nickname} resource_params={user.resource_params}")
    finally:
        db.close()


def account_reset_password(args):
    init_db()
    db = SessionLocal()
    try:
        user = _user(db, args.nickname)
        password = args.password or secrets.token_urlsafe(12)
        user.password_hash = _hash_password(password)
        db.query(AccountSession).filter(
            AccountSession.user_id == user.id,
            AccountSession.revoked_at.is_(None),
        ).update({"revoked_at": datetime.utcnow()})
        db.commit()
        print(f"reset password: {user.nickname}")
        if args.password:
            print("password: <provided>")
        else:
            print(f"password: {password}")
        print("active sessions revoked")
    finally:
        db.close()


def device_list(args):
    init_db()
    db = SessionLocal()
    try:
        user = _user(db, args.nickname)
        devices = db.query(UserDevice).filter(UserDevice.user_id == user.id).order_by(UserDevice.last_seen_at.desc()).all()
        if not devices:
            print(f"no devices for {user.nickname}")
            return
        for device in devices:
            print(
                f"{device.device_id}\tactive={device.active}\tname={device.device_name or '-'}\tplatform={device.platform or '-'}\tlast_seen={device.last_seen_at}"
            )
    finally:
        db.close()


def device_kick(args):
    init_db()
    db = SessionLocal()
    try:
        user = _user(db, args.nickname)
        device = db.query(UserDevice).filter(
            UserDevice.user_id == user.id,
            UserDevice.device_id == args.device_id,
        ).first()
        if not device:
            raise SystemExit(f"device not found: {args.device_id}")
        device.active = False
        db.query(AccountSession).filter(
            AccountSession.user_id == user.id,
            AccountSession.device_id == args.device_id,
            AccountSession.revoked_at.is_(None),
        ).update({"revoked_at": datetime.utcnow()})
        db.commit()
        print(f"kicked device: {args.nickname} {args.device_id}")
    finally:
        db.close()


EXAMPLES = """\
examples:
  familyhubctl.py account add alice --password 'Secret123!'
  familyhubctl.py account add test-user --password 'Secret123!' --account-type test --livestream-env test
  familyhubctl.py account list
  familyhubctl.py account disable alice
  familyhubctl.py account enable alice
  familyhubctl.py account reset-password alice --password 'NewSecret123!'
  familyhubctl.py account set-max-devices alice 5
  familyhubctl.py account set-type alice test
  familyhubctl.py account set-livestream-env alice test
  familyhubctl.py account set-livestream-env alice default
  familyhubctl.py account set-resource-json alice '{"livestream":{"env":"test"}}'
  familyhubctl.py account delete alice
  familyhubctl.py device list alice
  familyhubctl.py device kick alice <device_id>
"""


def build_parser():
    parser = argparse.ArgumentParser(
        prog="familyhubctl",
        description="Manage FamilyHub accounts and devices from the command line.",
        epilog=EXAMPLES,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = parser.add_subparsers(dest="area", required=True, metavar="{account,device}")

    account = sub.add_parser("account", help="create, list, and manage accounts")
    account_sub = account.add_subparsers(dest="command", required=True)

    add = account_sub.add_parser("add", help="create a new account")
    add.add_argument("nickname", help="unique login nickname for the account")
    add.add_argument("--password", help="account password (random one is generated and printed if omitted)")
    add.add_argument("--account-type", default="standard", help="account type, e.g. standard or test (default: standard)")
    add.add_argument("--livestream-env", choices=["live", "test"], help="pin this account's livestream room to live or test")
    add.add_argument("--resource-json", help="initial resource_params as a JSON object")
    add.add_argument("--max-devices", type=int, default=DEFAULT_MAX_DEVICES, help=f"max concurrent devices (default: {DEFAULT_MAX_DEVICES})")
    add.set_defaults(func=account_add)

    lst = account_sub.add_parser("list", help="list all accounts")
    lst.set_defaults(func=account_list)

    delete = account_sub.add_parser("delete", help="permanently delete an account and its data")
    delete.add_argument("nickname")
    delete.set_defaults(func=account_delete)

    disable = account_sub.add_parser("disable", help="disable an account and revoke its active sessions")
    disable.add_argument("nickname")
    disable.set_defaults(func=lambda args: account_status(args, "disabled"))

    enable = account_sub.add_parser("enable", help="re-enable a disabled account")
    enable.add_argument("nickname")
    enable.set_defaults(func=lambda args: account_status(args, "active"))

    maxdev = account_sub.add_parser("set-max-devices", help="change the max concurrent device count")
    maxdev.add_argument("nickname")
    maxdev.add_argument("max_devices", type=int)
    maxdev.set_defaults(func=account_set_max_devices)

    set_type = account_sub.add_parser("set-type", help="change the account_type, e.g. standard or test")
    set_type.add_argument("nickname")
    set_type.add_argument("account_type")
    set_type.set_defaults(func=account_set_type)

    liveenv = account_sub.add_parser("set-livestream-env", help="route this account to the live/test livestream room, or clear it")
    liveenv.add_argument("nickname")
    liveenv.add_argument("env", choices=["live", "test", "default"], help="'default' clears the override")
    liveenv.set_defaults(func=account_set_livestream_env)

    resource = account_sub.add_parser("set-resource-json", help="overwrite the account's resource_params JSON")
    resource.add_argument("nickname")
    resource.add_argument("resource_json", help="JSON object, e.g. '{\"livestream\":{\"env\":\"test\"}}'")
    resource.set_defaults(func=account_set_resource_json)

    reset = account_sub.add_parser("reset-password", help="reset an account's password and revoke its active sessions")
    reset.add_argument("nickname")
    reset.add_argument("--password", help="new password (random one is generated and printed if omitted)")
    reset.set_defaults(func=account_reset_password)

    device = sub.add_parser("device", help="inspect and kick logged-in devices")
    device_sub = device.add_subparsers(dest="command", required=True)

    dl = device_sub.add_parser("list", help="list devices logged into an account")
    dl.add_argument("nickname")
    dl.set_defaults(func=device_list)

    kick = device_sub.add_parser("kick", help="force a device to log out")
    kick.add_argument("nickname")
    kick.add_argument("device_id")
    kick.set_defaults(func=device_kick)

    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()
    if not hasattr(args, "func"):
        parser.print_help()
        raise SystemExit(1)
    args.func(args)


if __name__ == "__main__":
    main()
