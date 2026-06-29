package code.name.monkey.retromusic

import retrofit2.http.*

// ─── Request bodies ───────────────────────────────────────────────────────────

data class RegisterBody(val nickname: String, val password: String)
data class LoginBody(val nickname: String, val password: String)
data class FriendRequestBody(val nickname: String)

// ─── Response models ──────────────────────────────────────────────────────────

data class UserAuthResponse(val id: String, val nickname: String, val token: String)

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

// ─── Retrofit interface ───────────────────────────────────────────────────────

interface SyncTuneApi {

    @POST("users/register")
    suspend fun register(@Body body: RegisterBody): UserAuthResponse

    @POST("users/login")
    suspend fun login(@Body body: LoginBody): UserAuthResponse

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
}
