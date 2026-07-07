package code.name.monkey.retromusic

import okhttp3.MultipartBody
import retrofit2.http.*

// Request bodies

data class RegisterBody(val nickname: String, val password: String)
data class LoginBody(
    val nickname: String,
    val password: String,
    val device_id: String,
    val device_name: String? = null,
    val platform: String = "android",
)
data class FriendRequestBody(val nickname: String)
data class LiveRoomCreate(
    val title: String? = null,
    val source_type: String = "camera",
    val quality: String = "ultra",
)
data class LiveRoomQualityUpdate(val quality: String)

// Response models

data class UserAuthResponse(
    val id: String,
    val nickname: String,
    val token: String,
    val device_id: String? = null,
    val max_devices: Int? = null,
)

data class CurrentUserResponse(
    val id: String,
    val nickname: String,
    val status: String,
    val max_devices: Int,
)

data class UserInfo(val id: String, val nickname: String)

data class FriendRequestResponse(
    val id: String,
    val to_user_id: String,
    val to_nickname: String,
    val status: String,
)

data class FriendRequestInfo(
    val id: String,
    val from_user_id: String,
    val from_nickname: String,
    val created_at: String,
)

data class FriendInfo(val id: String, val nickname: String, val online: Boolean)

data class SimpleResponse(val detail: String)

data class LiveRoomInfo(
    val id: String,
    val title: String,
    val owner_id: String,
    val owner_nickname: String?,
    val status: String,
    val source_type: String,
    val quality: String,
    val livekit_room_name: String,
    val livekit_url: String,
    val livekit_enabled: Boolean,
    val created_at: String?,
    val started_at: String?,
    val ended_at: String?,
)

data class LiveRoomJoinInfo(
    val id: String,
    val title: String,
    val owner_id: String,
    val owner_nickname: String?,
    val status: String,
    val source_type: String,
    val quality: String,
    val livekit_room_name: String,
    val livekit_url: String,
    val livekit_enabled: Boolean,
    val role: String,
    val participant_identity: String,
    val participant_name: String,
    val publish_allowed: Boolean,
    val livekit_token: String?,
)

data class IntegrationLaunchResponse(
    val launch_url: String,
    val expires_in: Int,
)

data class CloudTrackInfo(
    val id: String,
    val title: String,
    val artist: String?,
    val duration_ms: Long?,
    val source: String?,
    val play_count: Int?,
    val created_at: String?,
)

data class TrackPlayResponse(
    val id: String,
    val play_count: Int,
)

// Retrofit interface

interface SyncTuneApi {

    @POST("users/register")
    suspend fun register(@Body body: RegisterBody): UserAuthResponse

    @POST("users/login")
    suspend fun login(@Body body: LoginBody): UserAuthResponse

    @GET("users/me")
    suspend fun me(
        @Header("X-Token") token: String,
    ): CurrentUserResponse

    @GET("users/search")
    suspend fun searchUsers(
        @Query("q") q: String,
        @Header("X-Token") token: String,
    ): List<UserInfo>

    @POST("users/friend-requests")
    suspend fun sendFriendRequest(
        @Body body: FriendRequestBody,
        @Header("X-Token") token: String,
    ): FriendRequestResponse

    @GET("users/friend-requests")
    suspend fun listFriendRequests(
        @Header("X-Token") token: String,
    ): List<FriendRequestInfo>

    @POST("users/friend-requests/{id}/accept")
    suspend fun acceptFriendRequest(
        @Path("id") id: String,
        @Header("X-Token") token: String,
    ): SimpleResponse

    @POST("users/friend-requests/{id}/reject")
    suspend fun rejectFriendRequest(
        @Path("id") id: String,
        @Header("X-Token") token: String,
    ): SimpleResponse

    @GET("users/friends")
    suspend fun listFriends(
        @Header("X-Token") token: String,
    ): List<FriendInfo>

    @DELETE("users/friends/{friendId}")
    suspend fun removeFriend(
        @Path("friendId") friendId: String,
        @Header("X-Token") token: String,
    ): SimpleResponse

    @GET("live/rooms")
    suspend fun listLiveRooms(
        @Header("X-Token") token: String,
    ): List<LiveRoomInfo>

    @GET("live/rooms/{id}")
    suspend fun getLiveRoom(
        @Path("id") id: String,
        @Header("X-Token") token: String,
    ): LiveRoomInfo

    @POST("live/rooms")
    suspend fun createLiveRoom(
        @Body body: LiveRoomCreate,
        @Header("X-Token") token: String,
    ): LiveRoomJoinInfo

    @POST("live/rooms/{id}/join")
    suspend fun joinLiveRoom(
        @Path("id") id: String,
        @Header("X-Token") token: String,
    ): LiveRoomJoinInfo

    @POST("live/rooms/{id}/end")
    suspend fun endLiveRoom(
        @Path("id") id: String,
        @Header("X-Token") token: String,
    ): SimpleResponse

    @POST("live/rooms/{id}/heartbeat")
    suspend fun heartbeatLiveRoom(
        @Path("id") id: String,
        @Header("X-Token") token: String,
    ): SimpleResponse

    @PATCH("live/rooms/{id}/quality")
    suspend fun updateLiveRoomQuality(
        @Path("id") id: String,
        @Body body: LiveRoomQualityUpdate,
        @Header("X-Token") token: String,
    ): LiveRoomInfo

    @POST("integrations/cinema/launch")
    suspend fun launchCinema(
        @Header("X-Token") token: String,
    ): IntegrationLaunchResponse

    @POST("integrations/cinema/admin/launch")
    suspend fun launchCinemaAdmin(
        @Header("X-Token") token: String,
    ): IntegrationLaunchResponse

    @POST("integrations/livestream/launch")
    suspend fun launchLivestream(
        @Header("X-Token") token: String,
        @Query("env") env: String? = null,
    ): IntegrationLaunchResponse

    @GET("tracks")
    suspend fun listCloudTracks(
        @Header("X-Token") token: String,
        @Query("sort") sort: String? = null,
    ): List<CloudTrackInfo>

    @GET("tracks/history")
    suspend fun listCloudTrackHistory(
        @Header("X-Token") token: String,
    ): List<CloudTrackInfo>

    @POST("tracks/{id}/play")
    suspend fun reportCloudTrackPlay(
        @Path("id") id: String,
        @Header("X-Token") token: String,
    ): TrackPlayResponse

    @Multipart
    @POST("upload")
    suspend fun uploadCloudTrack(
        @Part file: MultipartBody.Part,
        @Header("X-Token") token: String,
    ): CloudTrackInfo
}
