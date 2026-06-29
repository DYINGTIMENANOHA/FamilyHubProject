package code.name.monkey.retromusic.fragments.friends

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.FriendInfo
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.RoomRole
import code.name.monkey.retromusic.SyncController
import code.name.monkey.retromusic.SyncTuneSession
import code.name.monkey.retromusic.fragments.room.RoomControlSheet
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class FriendsFragment : Fragment() {

    private val TAG = "FriendsFragment"
    private val viewModel: FriendsViewModel by sharedViewModel()
    private val syncController: SyncController by inject()

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var btnRequests: MaterialButton
    private lateinit var fab: FloatingActionButton
    private lateinit var layoutRoomStatus: View
    private lateinit var tvRoomStatus: TextView
    private lateinit var btnRoomControls: MaterialButton

    private val adapter = FriendsAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_friends, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: user=${SyncTuneSession.nickname}")

        recyclerView = view.findViewById(R.id.rv_friends)
        emptyText = view.findViewById(R.id.tv_empty)
        btnRequests = view.findViewById(R.id.btn_friend_requests)
        fab = view.findViewById(R.id.fab_add_friend)
        layoutRoomStatus = view.findViewById(R.id.layout_room_status)
        tvRoomStatus = view.findViewById(R.id.tv_room_status)
        btnRoomControls = view.findViewById(R.id.btn_room_controls)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        adapter.onRemoveClick = { friend ->
            Log.i(TAG, "remove friend: ${friend.nickname}")
            viewModel.removeFriend(friend.id)
        }
        adapter.onFollowClick = { friend ->
            Log.i(TAG, "follow friend: ${friend.id} ${friend.nickname}")
            syncController.followFriend(friend.id)
            Snackbar.make(view, "Joining ${friend.nickname}'s room...", Snackbar.LENGTH_SHORT).show()
        }

        btnRequests.setOnClickListener {
            findNavController().navigate(R.id.friend_requests_fragment)
        }
        fab.setOnClickListener { showAddFriendDialog() }
        btnRoomControls.setOnClickListener {
            RoomControlSheet.newInstance().show(parentFragmentManager, "RoomControlSheet")
        }

        // Observe friends list
        viewModel.friends.observe(viewLifecycleOwner) { list ->
            Log.d(TAG, "friends updated: count=${list.size}")
            adapter.submitList(list)
            emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        // Observe pending requests count
        viewModel.pendingRequests.observe(viewLifecycleOwner) { reqs ->
            btnRequests.text = if (reqs.isNotEmpty()) "Requests (${reqs.size})" else "Requests"
        }

        // Observe errors
        viewModel.error.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                Snackbar.make(view, msg, Snackbar.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        // Observe room state to update banner
        viewLifecycleOwner.lifecycleScope.launch {
            syncController.roomState.collectLatest { state ->
                Log.d(TAG, "roomState updated: role=${state.role}")
                when (state.role) {
                    RoomRole.SOLO -> {
                        layoutRoomStatus.visibility = View.GONE
                    }
                    RoomRole.HOSTING -> {
                        layoutRoomStatus.visibility = View.VISIBLE
                        tvRoomStatus.text = "Hosting a room"
                    }
                    RoomRole.FOLLOWING -> {
                        layoutRoomStatus.visibility = View.VISIBLE
                        tvRoomStatus.text = "Following: ${state.hostNickname ?: "someone"}"
                    }
                }
            }
        }

        // Observe sync errors to show user-visible Snackbar
        viewLifecycleOwner.lifecycleScope.launch {
            syncController.errors.collect { errorMsg ->
                Log.w(TAG, "syncError: $errorMsg")
                Snackbar.make(view, errorMsg, Snackbar.LENGTH_LONG).show()
            }
        }

        viewModel.loadFriends()
        viewModel.loadPendingRequests()
    }

    private fun showAddFriendDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Enter nickname"
            setSingleLine()
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Add Friend")
            .setView(input)
            .setPositiveButton("Send Request") { _, _ ->
                val nick = input.text.toString().trim()
                if (nick.isNotEmpty()) {
                    viewModel.sendFriendRequest(nick) { _, msg ->
                        view?.let { v -> Snackbar.make(v, msg, Snackbar.LENGTH_SHORT).show() }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

// ─── Inline Adapter ────────────────────────────────────────────────────────────

private class FriendsAdapter : RecyclerView.Adapter<FriendsAdapter.VH>() {

    var onRemoveClick: ((FriendInfo) -> Unit)? = null
    var onFollowClick: ((FriendInfo) -> Unit)? = null
    private val items = mutableListOf<FriendInfo>()

    fun submitList(list: List<FriendInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_friend, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_friend_name)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_friend_status)
        private val btnFollow: MaterialButton = itemView.findViewById(R.id.btn_follow_friend)
        private val btnRemove: MaterialButton = itemView.findViewById(R.id.btn_remove_friend)

        fun bind(friend: FriendInfo) {
            tvName.text = friend.nickname
            tvStatus.text = if (friend.online) "● Online" else "○ Offline"
            tvStatus.setTextColor(
                if (friend.online) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()
            )
            // Follow button only for online friends
            btnFollow.visibility = if (friend.online) View.VISIBLE else View.GONE
            btnFollow.setOnClickListener { onFollowClick?.invoke(friend) }
            btnRemove.setOnClickListener { onRemoveClick?.invoke(friend) }
        }
    }
}
