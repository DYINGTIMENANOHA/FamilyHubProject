package code.name.monkey.retromusic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object SyncTuneSession {
    private const val TAG = "SyncTuneSession"
    private const val PREFS_NAME = "synctune_session"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_NICKNAME = "nickname"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "init: isLoggedIn=$isLoggedIn nickname=$nickname userId=$userId")
    }

    var token: String?
        get() = prefs?.getString(KEY_TOKEN, null)
        set(value) {
            Log.d(TAG, "token set: ${value?.take(8)}...")
            prefs?.edit()?.putString(KEY_TOKEN, value)?.apply()
        }

    var userId: String?
        get() = prefs?.getString(KEY_USER_ID, null)
        set(value) {
            Log.d(TAG, "userId set: $value")
            prefs?.edit()?.putString(KEY_USER_ID, value)?.apply()
        }

    var nickname: String?
        get() = prefs?.getString(KEY_NICKNAME, null)
        set(value) {
            Log.d(TAG, "nickname set: $value")
            prefs?.edit()?.putString(KEY_NICKNAME, value)?.apply()
        }

    val isLoggedIn: Boolean
        get() = token != null

    fun save(id: String, nick: String, tok: String) {
        userId = id
        nickname = nick
        token = tok
        Log.i(TAG, "session saved: nickname=$nick userId=$id")
    }

    fun clear() {
        Log.i(TAG, "session cleared (logout)")
        prefs?.edit()?.clear()?.apply()
    }
}
