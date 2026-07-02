package code.name.monkey.retromusic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.UUID

object SyncTuneSession {
    private const val TAG = "SyncTuneSession"
    private const val PREFS_NAME = "familyhub_session"
    private const val LEGACY_PREFS_NAME = "synctune_session"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MAX_DEVICES = "max_devices"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacySession(context)
        ensureDeviceId()
        Log.d(TAG, "init: isLoggedIn=$isLoggedIn nickname=$nickname userId=$userId deviceId=$deviceId")
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

    var maxDevices: Int?
        get() {
            val stored = prefs ?: return null
            return if (stored.contains(KEY_MAX_DEVICES)) stored.getInt(KEY_MAX_DEVICES, 0) else null
        }
        private set(value) {
            val editor = prefs?.edit() ?: return
            if (value == null) {
                editor.remove(KEY_MAX_DEVICES)
            } else {
                editor.putInt(KEY_MAX_DEVICES, value)
            }
            editor.apply()
        }

    val deviceId: String
        get() = ensureDeviceId()

    val isLoggedIn: Boolean
        get() = token != null

    fun save(id: String, nick: String, tok: String, deviceIdFromServer: String? = null, maxDevicesFromServer: Int? = null) {
        val editor = prefs?.edit() ?: return
        editor.putString(KEY_USER_ID, id)
        editor.putString(KEY_NICKNAME, nick)
        editor.putString(KEY_TOKEN, tok)
        if (!deviceIdFromServer.isNullOrBlank()) {
            editor.putString(KEY_DEVICE_ID, deviceIdFromServer)
        }
        if (maxDevicesFromServer != null) {
            editor.putInt(KEY_MAX_DEVICES, maxDevicesFromServer)
        }
        editor.apply()
        Log.i(TAG, "session saved: nickname=$nick userId=$id deviceId=$deviceId maxDevices=$maxDevices")
    }

    fun clear() {
        Log.i(TAG, "session cleared (logout)")
        val currentDeviceId = deviceId
        prefs?.edit()?.clear()?.putString(KEY_DEVICE_ID, currentDeviceId)?.apply()
    }

    private fun ensureDeviceId(): String {
        val stored = prefs?.getString(KEY_DEVICE_ID, null)
        if (!stored.isNullOrBlank()) return stored

        val generated = "android-${UUID.randomUUID()}"
        prefs?.edit()?.putString(KEY_DEVICE_ID, generated)?.apply()
        Log.i(TAG, "generated deviceId=$generated")
        return generated
    }

    private fun migrateLegacySession(context: Context) {
        val currentPrefs = prefs ?: return
        if (currentPrefs.contains(KEY_DEVICE_ID) || currentPrefs.contains(KEY_TOKEN)) return

        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (!legacy.contains(KEY_TOKEN) && !legacy.contains(KEY_USER_ID) && !legacy.contains(KEY_NICKNAME)) return

        currentPrefs.edit()
            .putString(KEY_TOKEN, legacy.getString(KEY_TOKEN, null))
            .putString(KEY_USER_ID, legacy.getString(KEY_USER_ID, null))
            .putString(KEY_NICKNAME, legacy.getString(KEY_NICKNAME, null))
            .apply()
        Log.i(TAG, "legacy SyncTune session migrated")
    }
}
