package code.name.monkey.retromusic

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class PresenceUpdate(val userId: String, val online: Boolean)

class SyncTuneWebSocketManager(private val okHttpClient: OkHttpClient) {

    private val TAG = "SyncTuneWS"

    private var webSocket: WebSocket? = null
    private var lastToken: String? = null

    private val _presenceUpdates = MutableSharedFlow<PresenceUpdate>(replay = 0, extraBufferCapacity = 32)
    val presenceUpdates: SharedFlow<PresenceUpdate> = _presenceUpdates.asSharedFlow()

    private val _roomEvents = MutableSharedFlow<RoomEvent>(replay = 0, extraBufferCapacity = 64)
    val roomEvents: SharedFlow<RoomEvent> = _roomEvents.asSharedFlow()

    // ── Connection ─────────────────────────────────────────────────────────────

    fun connect(token: String) {
        if (webSocket != null) {
            Log.d(TAG, "connect: already connected, ignoring")
            return
        }
        lastToken = token
        val wsUrl = SyncTuneConfig.SERVER_BASE_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/ws?token=$token"
        Log.i(TAG, "connect: url=$wsUrl")
        val request = Request.Builder().url(wsUrl).build()
        webSocket = okHttpClient.newWebSocket(request, listener)
    }

    fun reconnect() {
        webSocket = null
        lastToken?.let { connect(it) }
    }

    fun disconnect() {
        Log.i(TAG, "disconnect: closing WebSocket")
        webSocket?.close(1000, "user logout")
        webSocket = null
        lastToken = null
    }

    val isConnected: Boolean get() = webSocket != null

    // ── Send ───────────────────────────────────────────────────────────────────

    fun send(json: JSONObject): Boolean {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "send: not connected, dropping ${json.optString("type")}")
            return false
        }
        val payload = json.toString()
        Log.d(TAG, "send: $payload")
        return ws.send(payload)
    }

    // ── Listener ───────────────────────────────────────────────────────────────

    private val listener = object : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "onOpen: connected code=${response.code}")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.d(TAG, "onMessage: $text")
            try {
                val json = JSONObject(text)
                when (val type = json.optString("type")) {
                    "PRESENCE_UPDATE" -> {
                        val uid = json.getString("user_id")
                        val online = json.getBoolean("online")
                        Log.i(TAG, "PRESENCE_UPDATE uid=$uid online=$online")
                        _presenceUpdates.tryEmit(PresenceUpdate(uid, online))
                    }
                    "ROOM_STATE" -> {
                        val roleStr = json.optString("role", "FOLLOWING")
                        val role = when (roleStr) {
                            "HOSTING" -> RoomRole.HOSTING
                            "FOLLOWING" -> RoomRole.FOLLOWING
                            else -> RoomRole.SOLO
                        }
                        val event = RoomEvent.RoomState(
                            role = role,
                            roomId = json.optString("room_id", ""),
                            hostId = json.optString("host_id").takeIf { it.isNotEmpty() },
                            hostNickname = json.optString("host_nickname").takeIf { it.isNotEmpty() },
                            trackId = if (json.isNull("track_id")) null else json.optString("track_id").takeIf { it.isNotEmpty() },
                            positionMs = json.optLong("position_ms", 0),
                            isPlaying = json.optBoolean("is_playing", false),
                            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                            allowGuestControl = json.optBoolean("allow_guest_control", false),
                        )
                        Log.i(TAG, "ROOM_STATE role=$roleStr roomId=${event.roomId}")
                        _roomEvents.tryEmit(event)
                    }
                    "ROOM_DISSOLVED" -> {
                        val event = RoomEvent.RoomDissolved(
                            roomId = json.optString("room_id", ""),
                            reason = json.optString("reason", "unknown"),
                        )
                        Log.i(TAG, "ROOM_DISSOLVED reason=${event.reason}")
                        _roomEvents.tryEmit(event)
                    }
                    "ROOM_SETTINGS" -> {
                        _roomEvents.tryEmit(
                            RoomEvent.RoomSettings(
                                roomId = json.optString("room_id", ""),
                                allowGuestControl = json.optBoolean("allow_guest_control", false),
                            )
                        )
                    }
                    "SYNC_FORCE" -> {
                        val event = RoomEvent.SyncForce(
                            trackId = if (json.isNull("track_id")) null else json.optString("track_id").takeIf { it.isNotEmpty() },
                            positionMs = json.optLong("position_ms", 0),
                            isPlaying = json.optBoolean("is_playing", false),
                            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                            senderId = json.optString("sender_id", ""),
                        )
                        Log.i(TAG, "SYNC_FORCE track=${event.trackId} pos=${event.positionMs} playing=${event.isPlaying}")
                        _roomEvents.tryEmit(event)
                    }
                    "SYNC_PLAY" -> _roomEvents.tryEmit(
                        RoomEvent.SyncPlay(
                            senderId = json.optString("sender_id", ""),
                            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                        )
                    )
                    "SYNC_PAUSE" -> _roomEvents.tryEmit(
                        RoomEvent.SyncPause(
                            senderId = json.optString("sender_id", ""),
                            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                        )
                    )
                    "SYNC_SEEK" -> _roomEvents.tryEmit(
                        RoomEvent.SyncSeek(
                            positionMs = json.optLong("position_ms", 0),
                            senderId = json.optString("sender_id", ""),
                            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                        )
                    )
                    "ERROR" -> {
                        val detail = json.optString("detail", "unknown error")
                        Log.w(TAG, "SERVER_ERROR: $detail")
                        _roomEvents.tryEmit(RoomEvent.Error(detail))
                    }
                    "ACK" -> Log.d(TAG, "ACK: ${json.optString("detail")}")
                    "ROOM_MEMBER_UPDATE" -> Log.d(TAG, "ROOM_MEMBER_UPDATE members=${json.optJSONArray("members")}")
                    else -> Log.d(TAG, "unhandled type: $type")
                }
            } catch (e: Exception) {
                Log.w(TAG, "onMessage parse error: ${e.message}")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "onFailure: ${t.message} response=${response?.code}")
            webSocket = null
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "onClosed: code=$code reason=$reason")
            webSocket = null
        }
    }
}
