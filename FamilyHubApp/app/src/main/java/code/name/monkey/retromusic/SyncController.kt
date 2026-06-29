package code.name.monkey.retromusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.repository.SongRepository
import code.name.monkey.retromusic.service.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SyncController(
    context: Context,
    private val wsManager: SyncTuneWebSocketManager,
    private val songRepository: SongRepository,
) {
    private val TAG = "SyncController"
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Public state ───────────────────────────────────────────────────────────

    private val _roomState = MutableStateFlow(RoomControlState())
    val roomState: StateFlow<RoomControlState> = _roomState.asStateFlow()

    private val _errors = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    val role: RoomRole get() = _roomState.value.role
    val roomId: String get() = _roomState.value.roomId
    val allowGuestControl: Boolean get() = _roomState.value.allowGuestControl

    // ── Sync guard ─────────────────────────────────────────────────────────────

    @Volatile
    var isSyncing: Boolean = false
        private set

    // When a sync-induced track switch is pending, store the target seek position here
    @Volatile
    private var pendingSeekMs: Long = -1L
    @Volatile
    private var pendingIsPlaying: Boolean? = null

    // Seek detection: track last known playback state
    private var lastPositionMs: Long = 0L
    private var lastPositionTimestamp: Long = 0L
    private var wasPlaying: Boolean = false

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    private var started = false

    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "start: registering receiver and observing room events")

        // 初始化位置跟踪，避免首次 seek 因 lastPositionTimestamp=0 检测不到
        lastPositionMs = MusicPlayerRemote.songProgressMillis.toLong().coerceAtLeast(0L)
        lastPositionTimestamp = System.currentTimeMillis()
        wasPlaying = MusicPlayerRemote.isPlaying

        val filter = IntentFilter().apply {
            addAction(MusicService.META_CHANGED)
            addAction(MusicService.PLAY_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(broadcastReceiver, filter)
        }

        scope.launch {
            wsManager.roomEvents.collect { event ->
                Log.d(TAG, "roomEvent: ${event::class.simpleName}")
                handleRoomEvent(event)
            }
        }
    }

    fun stop() {
        Log.i(TAG, "stop: unregistering receiver")
        try {
            appContext.unregisterReceiver(broadcastReceiver)
        } catch (_: Exception) {}
        started = false
    }

    // ── BroadcastReceiver ──────────────────────────────────────────────────────

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MusicService.META_CHANGED -> onTrackChanged()
                MusicService.PLAY_STATE_CHANGED -> onPlayStateChanged()
            }
        }
    }

    private fun onTrackChanged() {
        // Check for pending sync-induced track change first
        val seekMs = pendingSeekMs
        val playState = pendingIsPlaying
        if (seekMs >= 0L) {
            pendingSeekMs = -1L
            pendingIsPlaying = null
            Log.i(TAG, "onTrackChanged: applying pending seek=$seekMs playing=$playState")
            mainHandler.postDelayed({
                setSyncing(true, 800L)
                MusicPlayerRemote.seekTo(seekMs.toInt())
                playState?.let { play ->
                    if (play && !MusicPlayerRemote.isPlaying) MusicPlayerRemote.resumePlaying()
                    else if (!play && MusicPlayerRemote.isPlaying) MusicPlayerRemote.pauseSong()
                }
            }, 400L)
            return
        }

        if (isSyncing) return
        val canBroadcast = role == RoomRole.HOSTING || (role == RoomRole.FOLLOWING && allowGuestControl)
        if (!canBroadcast) return

        val song = MusicPlayerRemote.currentSong
        val trackId = extractTrackId(song.data) ?: return
        val positionMs = MusicPlayerRemote.songProgressMillis.toLong().coerceAtLeast(0L)
        val isPlaying = MusicPlayerRemote.isPlaying
        val timestamp = System.currentTimeMillis()

        lastPositionMs = positionMs
        lastPositionTimestamp = timestamp
        wasPlaying = isPlaying

        Log.i(TAG, "onTrackChanged: broadcasting SYNC_ALL track=$trackId pos=$positionMs playing=$isPlaying")
        wsManager.send(JSONObject().apply {
            put("type", "SYNC_ALL")
            put("track_id", trackId)
            put("position_ms", positionMs)
            put("is_playing", isPlaying)
            put("timestamp", timestamp)
        })
    }

    private fun onPlayStateChanged() {
        if (isSyncing) return
        val canBroadcast = role == RoomRole.HOSTING || (role == RoomRole.FOLLOWING && allowGuestControl)
        if (!canBroadcast) return

        val isPlaying = MusicPlayerRemote.isPlaying
        val positionMs = MusicPlayerRemote.songProgressMillis.toLong().coerceAtLeast(0L)
        val now = System.currentTimeMillis()

        // Detect manual seek: compare actual position vs expected position from normal playback
        val expectedPosition = if (wasPlaying && lastPositionTimestamp > 0L) {
            lastPositionMs + (now - lastPositionTimestamp)
        } else {
            lastPositionMs
        }
        val seekDelta = Math.abs(positionMs - expectedPosition)
        val isSeeked = seekDelta > 3000L && lastPositionTimestamp > 0L

        if (isSeeked) {
            Log.i(TAG, "onPlayStateChanged: seek detected delta=${seekDelta}ms → SYNC_SEEK pos=$positionMs")
            wsManager.send(JSONObject().apply {
                put("type", "SYNC_SEEK")
                put("position_ms", positionMs)
                put("timestamp", now)
            })
        }

        if (isPlaying != wasPlaying) {
            val syncType = if (isPlaying) "SYNC_PLAY" else "SYNC_PAUSE"
            Log.i(TAG, "onPlayStateChanged: play state changed → $syncType pos=$positionMs")
            wsManager.send(JSONObject().apply {
                put("type", syncType)
                put("position_ms", positionMs)
                put("timestamp", now)
            })
        }

        lastPositionMs = positionMs
        lastPositionTimestamp = now
        wasPlaying = isPlaying
    }

    // ── Room event handler ─────────────────────────────────────────────────────

    private fun handleRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.RoomState -> {
                Log.i(TAG, "handleRoomEvent ROOM_STATE role=${event.role} host=${event.hostNickname}")
                _roomState.value = RoomControlState(
                    role = event.role,
                    roomId = event.roomId,
                    hostId = event.hostId,
                    hostNickname = event.hostNickname,
                    allowGuestControl = event.allowGuestControl,
                )
                when (event.role) {
                    RoomRole.HOSTING -> {
                        // 有 follower 刚加入，立即把当前播放状态广播出去让 follower 同步
                        syncAll()
                    }
                    RoomRole.FOLLOWING -> {
                        // 如果服务器已有房间状态（host 之前发过 SYNC_ALL），直接同步
                        // 如果为空（房间刚建立），host 接到 HOSTING 通知后会自动调 syncAll()，
                        // 那里发出的 SYNC_FORCE 会推到 Soul，不需要我们再发 ROOM_CATCHUP
                        if (event.trackId != null) {
                            applySyncForce(
                                RoomEvent.SyncForce(
                                    trackId = event.trackId,
                                    positionMs = event.positionMs,
                                    isPlaying = event.isPlaying,
                                    timestamp = event.timestamp,
                                    senderId = event.hostId ?: "",
                                )
                            )
                        }
                    }
                    RoomRole.SOLO -> Unit
                }
            }
            is RoomEvent.RoomDissolved -> {
                Log.i(TAG, "handleRoomEvent ROOM_DISSOLVED reason=${event.reason}")
                _roomState.value = RoomControlState()
            }
            is RoomEvent.RoomSettings -> {
                Log.i(TAG, "handleRoomEvent ROOM_SETTINGS allowGuestControl=${event.allowGuestControl}")
                _roomState.value = _roomState.value.copy(allowGuestControl = event.allowGuestControl)
            }
            is RoomEvent.SyncForce -> {
                if (event.senderId != SyncTuneSession.userId) {
                    applySyncForce(event)
                }
            }
            is RoomEvent.SyncPlay -> {
                if (event.senderId != SyncTuneSession.userId) {
                    Log.i(TAG, "handleRoomEvent SYNC_PLAY from=${event.senderId}")
                    setSyncing(true, 500L)
                    mainHandler.post { MusicPlayerRemote.resumePlaying() }
                }
            }
            is RoomEvent.SyncPause -> {
                if (event.senderId != SyncTuneSession.userId) {
                    Log.i(TAG, "handleRoomEvent SYNC_PAUSE from=${event.senderId}")
                    setSyncing(true, 500L)
                    mainHandler.post { MusicPlayerRemote.pauseSong() }
                }
            }
            is RoomEvent.SyncSeek -> {
                if (event.senderId != SyncTuneSession.userId) {
                    val compensated = event.positionMs + (System.currentTimeMillis() - event.timestamp)
                    Log.i(TAG, "handleRoomEvent SYNC_SEEK pos=${event.positionMs} compensated=$compensated")
                    setSyncing(true, 500L)
                    mainHandler.post { MusicPlayerRemote.seekTo(compensated.toInt()) }
                }
            }
            is RoomEvent.Error -> {
                Log.w(TAG, "handleRoomEvent ERROR detail=${event.detail}")
                _errors.tryEmit(event.detail)
            }
        }
    }

    private fun applySyncForce(event: RoomEvent.SyncForce) {
        val compensatedPosition = event.positionMs + (System.currentTimeMillis() - event.timestamp)
        val currentTrackId = extractTrackId(MusicPlayerRemote.currentSong.data)
        Log.i(
            TAG,
            "applySyncForce: wantTrack=${event.trackId} currentTrack=$currentTrackId " +
            "pos=$compensatedPosition playing=${event.isPlaying}"
        )

        if (event.trackId != null && event.trackId != currentTrackId) {
            // Need to switch track
            pendingSeekMs = compensatedPosition
            pendingIsPlaying = event.isPlaying
            setSyncing(true, 5000L)  // long guard while track loads

            scope.launch {
                try {
                    val songs = withContext(Dispatchers.IO) { songRepository.songs() }
                    val target = songs.find { extractTrackId(it.data) == event.trackId }
                    if (target != null) {
                        Log.i(TAG, "applySyncForce: switching to track=${target.title}")
                        withContext(Dispatchers.Main) {
                            MusicPlayerRemote.openQueue(listOf(target), 0, true)
                            // pendingSeekMs / pendingIsPlaying will be applied in onTrackChanged
                        }
                    } else {
                        Log.w(TAG, "applySyncForce: track not found in library: ${event.trackId}")
                        pendingSeekMs = -1L
                        pendingIsPlaying = null
                        isSyncing = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "applySyncForce error: ${e.message}")
                    pendingSeekMs = -1L
                    pendingIsPlaying = null
                    isSyncing = false
                }
            }
        } else {
            // Same track — seek and set play state
            setSyncing(true, 800L)
            mainHandler.post {
                MusicPlayerRemote.seekTo(compensatedPosition.toInt())
                if (event.isPlaying && !MusicPlayerRemote.isPlaying) {
                    MusicPlayerRemote.resumePlaying()
                } else if (!event.isPlaying && MusicPlayerRemote.isPlaying) {
                    MusicPlayerRemote.pauseSong()
                }
            }
        }
    }

    // ── Public actions ─────────────────────────────────────────────────────────

    fun followFriend(hostId: String) {
        Log.i(TAG, "followFriend: host=$hostId connected=${wsManager.isConnected}")
        if (!wsManager.isConnected) {
            _errors.tryEmit("未连接到服务器，请检查网络")
            return
        }
        wsManager.send(JSONObject().apply {
            put("type", "ROOM_JOIN")
            put("host_id", hostId)
        })
    }

    fun leaveRoom() {
        Log.i(TAG, "leaveRoom")
        wsManager.send(JSONObject().apply {
            put("type", "ROOM_LEAVE")
        })
    }

    fun catchUp() {
        Log.i(TAG, "catchUp: sending ROOM_CATCHUP")
        wsManager.send(JSONObject().apply {
            put("type", "ROOM_CATCHUP")
        })
    }

    fun syncAll() {
        val song = MusicPlayerRemote.currentSong
        val trackId = extractTrackId(song.data)
        if (trackId == null) {
            Log.w(TAG, "syncAll: no SyncTune track playing, nothing to broadcast")
            return
        }
        val positionMs = MusicPlayerRemote.songProgressMillis.toLong().coerceAtLeast(0L)
        val isPlaying = MusicPlayerRemote.isPlaying
        val timestamp = System.currentTimeMillis()
        Log.i(TAG, "syncAll: track=$trackId pos=$positionMs playing=$isPlaying")
        wsManager.send(JSONObject().apply {
            put("type", "SYNC_ALL")
            put("track_id", trackId)
            put("position_ms", positionMs)
            put("is_playing", isPlaying)
            put("timestamp", timestamp)
        })
    }

    fun setGuestControl(allow: Boolean) {
        Log.i(TAG, "setGuestControl: allow=$allow")
        wsManager.send(JSONObject().apply {
            put("type", "ROOM_SETTINGS")
            put("allow_guest_control", allow)
        })
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun setSyncing(value: Boolean, clearAfterMs: Long = 0L) {
        isSyncing = value
        if (value && clearAfterMs > 0L) {
            mainHandler.postDelayed({ isSyncing = false }, clearAfterMs)
        }
    }

    companion object {
        // Extracts the UUID from a URL like http://host/stream/{uuid}
        fun extractTrackId(dataUrl: String?): String? {
            if (dataUrl.isNullOrEmpty()) return null
            val prefix = "/stream/"
            val start = dataUrl.lastIndexOf(prefix)
            if (start < 0) return null
            return dataUrl.substring(start + prefix.length).takeIf { it.isNotEmpty() }
        }
    }
}
