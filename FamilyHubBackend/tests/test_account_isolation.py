import unittest

from fastapi import HTTPException
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from database import Base
from models import Friendship, LiveRoom, User
from routers import live, users
from services.account_scope import account_scope, same_account_scope
from services.room_manager import RoomManager
from services.sync_manager import SyncTuneConnectionManager


class AccountIsolationTests(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine("sqlite:///:memory:")
        Base.metadata.create_all(self.engine)
        self.db = sessionmaker(bind=self.engine)()
        self.production = self._user("prod", "Production", "standard")
        self.production_friend = self._user("prod-friend", "Production Friend", "standard")
        self.test = self._user("test", "Test", "test")
        self.test_friend = self._user("test-friend", "Test Friend", "TEST")
        self.db.commit()

    def tearDown(self):
        self.db.close()
        self.engine.dispose()

    def _user(self, user_id, nickname, account_type):
        user = User(
            id=user_id,
            nickname=nickname,
            token=f"token-{user_id}",
            account_type=account_type,
        )
        self.db.add(user)
        return user

    def _live_room(self, room_id, owner):
        room = LiveRoom(
            id=room_id,
            title=room_id,
            owner_id=owner.id,
            livekit_room_name=f"livekit-{room_id}",
            status="live",
        )
        self.db.add(room)
        self.db.commit()
        return room

    def test_scope_normalizes_test_and_treats_other_types_as_production(self):
        self.assertEqual(account_scope(self.test), "test")
        self.assertEqual(account_scope(self.test_friend), "test")
        self.assertEqual(account_scope(self.production), "production")
        self.assertTrue(same_account_scope(self.test, self.test_friend))
        self.assertFalse(same_account_scope(self.test, self.production))

    def test_user_search_and_friend_list_hide_other_scope(self):
        results = users.search_users("Production", user=self.test, db=self.db)
        self.assertEqual(results, [])

        self.db.add(Friendship(user_id=self.test.id, friend_id=self.production.id))
        self.db.add(Friendship(user_id=self.test.id, friend_id=self.test_friend.id))
        self.db.commit()
        friends = users.list_friends(user=self.test, db=self.db)
        self.assertEqual([friend["id"] for friend in friends], [self.test_friend.id])

    def test_cross_scope_friend_request_looks_like_missing_user(self):
        with self.assertRaises(HTTPException) as raised:
            users.send_friend_request(
                users.FriendRequestCreate(nickname=self.production.nickname),
                user=self.test,
                db=self.db,
            )
        self.assertEqual(raised.exception.status_code, 404)

    def test_presence_friend_query_only_returns_same_scope(self):
        self.db.add(Friendship(user_id=self.test.id, friend_id=self.production.id))
        self.db.add(Friendship(user_id=self.test.id, friend_id=self.test_friend.id))
        self.db.commit()
        friends = SyncTuneConnectionManager._same_scope_friends(self.test.id, self.db)
        self.assertEqual([friend.friend_id for friend in friends], [self.test_friend.id])

    def test_live_room_list_and_direct_access_are_isolated(self):
        production_room = self._live_room("production-room", self.production)
        test_room = self._live_room("test-room", self.test)

        listed = live.list_rooms(user=self.test, db=self.db)
        self.assertEqual([room["id"] for room in listed], [test_room.id])

        with self.assertRaises(HTTPException) as get_error:
            live.get_room(production_room.id, user=self.test, db=self.db)
        self.assertEqual(get_error.exception.status_code, 404)

        with self.assertRaises(HTTPException) as join_error:
            live.join_room(production_room.id, user=self.test, db=self.db)
        self.assertEqual(join_error.exception.status_code, 404)

    def test_music_room_rejects_cross_scope_without_leaving_current_room(self):
        rooms = RoomManager()
        production_room = rooms.create_room("prod-host", "production")
        test_room = rooms.create_room("test-host", "test")
        rooms.join_room("test-host", "test-listener", "test")

        rejected = rooms.join_room("prod-host", "test-listener", "test")
        self.assertIsNone(rejected)
        self.assertEqual(rooms.get_room_by_user("test-listener").id, test_room.id)
        self.assertNotIn("test-listener", production_room.member_ids)


if __name__ == "__main__":
    unittest.main()
