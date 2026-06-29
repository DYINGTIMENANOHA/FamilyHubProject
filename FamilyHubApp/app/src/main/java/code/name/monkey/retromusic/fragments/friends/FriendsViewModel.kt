package code.name.monkey.retromusic.fragments.friends

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import code.name.monkey.retromusic.FriendInfo
import code.name.monkey.retromusic.FriendRequestBody
import code.name.monkey.retromusic.FriendRequestInfo
import code.name.monkey.retromusic.SyncTuneApi
import code.name.monkey.retromusic.SyncTuneSession
import code.name.monkey.retromusic.SyncTuneWebSocketManager
import kotlinx.coroutines.launch

class FriendsViewModel(
    private val api: SyncTuneApi,
    private val wsManager: SyncTuneWebSocketManager,
) : ViewModel() {

    private val TAG = "FriendsVM"

    private val _friends = MutableLiveData<List<FriendInfo>>(emptyList())
    val friends: LiveData<List<FriendInfo>> = _friends

    private val _pendingRequests = MutableLiveData<List<FriendRequestInfo>>(emptyList())
    val pendingRequests: LiveData<List<FriendRequestInfo>> = _pendingRequests

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    init {
        Log.d(TAG, "init")
        viewModelScope.launch {
            wsManager.presenceUpdates.collect { update ->
                Log.i(TAG, "presence update: userId=${update.userId} online=${update.online}")
                val current = _friends.value ?: return@collect
                val updated = current.map { f ->
                    if (f.id == update.userId) f.copy(online = update.online) else f
                }
                _friends.postValue(updated)
            }
        }
    }

    fun loadFriends() {
        val token = SyncTuneSession.token ?: return
        Log.d(TAG, "loadFriends")
        viewModelScope.launch {
            _loading.value = true
            runCatching { api.listFriends(token) }
                .onSuccess {
                    Log.i(TAG, "loadFriends: count=${it.size}")
                    _friends.value = it
                }
                .onFailure {
                    Log.e(TAG, "loadFriends error: ${it.message}")
                    _error.value = "Failed to load friends: ${it.message}"
                }
            _loading.value = false
        }
    }

    fun loadPendingRequests() {
        val token = SyncTuneSession.token ?: return
        Log.d(TAG, "loadPendingRequests")
        viewModelScope.launch {
            runCatching { api.listFriendRequests(token) }
                .onSuccess {
                    Log.i(TAG, "loadPendingRequests: count=${it.size}")
                    _pendingRequests.value = it
                }
                .onFailure {
                    Log.e(TAG, "loadPendingRequests error: ${it.message}")
                    _error.value = "Failed to load requests: ${it.message}"
                }
        }
    }

    fun sendFriendRequest(nickname: String, onDone: (Boolean, String) -> Unit) {
        val token = SyncTuneSession.token ?: return
        Log.i(TAG, "sendFriendRequest: to=$nickname")
        viewModelScope.launch {
            runCatching { api.sendFriendRequest(FriendRequestBody(nickname), token) }
                .onSuccess {
                    Log.i(TAG, "sendFriendRequest success: id=${it.id}")
                    onDone(true, "Request sent to ${it.to_nickname}")
                }
                .onFailure {
                    Log.e(TAG, "sendFriendRequest error: ${it.message}")
                    onDone(false, it.message ?: "Error sending request")
                }
        }
    }

    fun acceptRequest(requestId: String) {
        val token = SyncTuneSession.token ?: return
        Log.i(TAG, "acceptRequest: id=$requestId")
        viewModelScope.launch {
            runCatching { api.acceptFriendRequest(requestId, token) }
                .onSuccess {
                    Log.i(TAG, "acceptRequest success")
                    loadPendingRequests()
                    loadFriends()
                }
                .onFailure { Log.e(TAG, "acceptRequest error: ${it.message}") }
        }
    }

    fun rejectRequest(requestId: String) {
        val token = SyncTuneSession.token ?: return
        Log.i(TAG, "rejectRequest: id=$requestId")
        viewModelScope.launch {
            runCatching { api.rejectFriendRequest(requestId, token) }
                .onSuccess {
                    Log.i(TAG, "rejectRequest success")
                    loadPendingRequests()
                }
                .onFailure { Log.e(TAG, "rejectRequest error: ${it.message}") }
        }
    }

    fun removeFriend(friendId: String) {
        val token = SyncTuneSession.token ?: return
        Log.i(TAG, "removeFriend: id=$friendId")
        viewModelScope.launch {
            runCatching { api.removeFriend(friendId, token) }
                .onSuccess {
                    Log.i(TAG, "removeFriend success")
                    loadFriends()
                }
                .onFailure { Log.e(TAG, "removeFriend error: ${it.message}") }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
