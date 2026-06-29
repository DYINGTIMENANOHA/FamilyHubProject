package code.name.monkey.retromusic

enum class RoomRole { SOLO, HOSTING, FOLLOWING }

data class RoomControlState(
    val role: RoomRole = RoomRole.SOLO,
    val roomId: String = "",
    val hostId: String? = null,
    val hostNickname: String? = null,
    val allowGuestControl: Boolean = false,
)

sealed class RoomEvent {
    data class RoomState(
        val role: RoomRole,
        val roomId: String,
        val hostId: String?,
        val hostNickname: String?,
        val trackId: String?,
        val positionMs: Long,
        val isPlaying: Boolean,
        val timestamp: Long,
        val allowGuestControl: Boolean,
    ) : RoomEvent()

    data class RoomDissolved(val roomId: String, val reason: String) : RoomEvent()
    data class RoomSettings(val roomId: String, val allowGuestControl: Boolean) : RoomEvent()

    data class SyncForce(
        val trackId: String?,
        val positionMs: Long,
        val isPlaying: Boolean,
        val timestamp: Long,
        val senderId: String,
    ) : RoomEvent()

    data class SyncPlay(val senderId: String, val timestamp: Long) : RoomEvent()
    data class SyncPause(val senderId: String, val timestamp: Long) : RoomEvent()
    data class SyncSeek(val positionMs: Long, val senderId: String, val timestamp: Long) : RoomEvent()
    data class Error(val detail: String) : RoomEvent()
}
