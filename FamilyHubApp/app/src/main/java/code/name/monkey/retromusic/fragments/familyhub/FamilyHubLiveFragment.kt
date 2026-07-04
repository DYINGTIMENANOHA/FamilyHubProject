package code.name.monkey.retromusic.fragments.familyhub

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.LiveRoomCreate
import code.name.monkey.retromusic.LiveRoomInfo
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SyncTuneApi
import code.name.monkey.retromusic.SyncTuneSession
import code.name.monkey.retromusic.activities.LiveRoomActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class FamilyHubLiveFragment : Fragment(R.layout.fragment_familyhub_live) {
    private val api: SyncTuneApi by inject()
    private val adapter = LiveRoomAdapter()

    private lateinit var emptyText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var createButton: MaterialButton
    private lateinit var refreshButton: MaterialButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyText = view.findViewById(R.id.live_empty)
        progress = view.findViewById(R.id.live_progress)
        createButton = view.findViewById(R.id.live_create)
        refreshButton = view.findViewById(R.id.live_refresh)

        view.findViewById<RecyclerView>(R.id.live_rooms).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@FamilyHubLiveFragment.adapter
        }

        adapter.onJoin = { room -> joinRoom(room) }
        adapter.onEnd = { room -> endRoom(room) }
        createButton.setOnClickListener { showCreateDialog() }
        refreshButton.setOnClickListener { loadRooms() }
        loadRooms()
    }

    override fun onResume() {
        super.onResume()
        if (::emptyText.isInitialized) {
            loadRooms()
        }
    }

    private fun loadRooms() {
        val token = SyncTuneSession.token ?: return
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.listLiveRooms(token) }
                .onSuccess { rooms ->
                    adapter.submitList(rooms)
                    emptyText.visibility = if (rooms.isEmpty()) View.VISIBLE else View.GONE
                }
                .onFailure { showMessage("Failed to load live rooms: ${it.message}") }
            setLoading(false)
        }
    }

    private fun showCreateDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Room title"
            setSingleLine()
            setText("${SyncTuneSession.nickname ?: "FamilyHub"}'s Live")
        }
        val sourceGroup = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.VERTICAL
        }
        val cameraOption = RadioButton(requireContext()).apply {
            id = View.generateViewId()
            text = "Camera live"
            isChecked = true
        }
        val screenOption = RadioButton(requireContext()).apply {
            id = View.generateViewId()
            text = "Screen share"
        }
        sourceGroup.addView(cameraOption)
        sourceGroup.addView(screenOption)
        val qualityGroup = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.VERTICAL
        }
        val qualityOptions = listOf(
            "Extreme - 1440p 120fps" to "extreme",
            "Ultra - 1440p 60fps" to "ultra",
            "High - 1080p high quality" to "high",
            "HD - 720p high frame rate" to "hd",
            "Standard - balanced" to "standard",
            "Smooth - weak network fallback" to "smooth",
        ).map { (label, value) ->
            RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = label
                tag = value
                isChecked = value == "ultra"
            }
        }
        qualityOptions.forEach { qualityGroup.addView(it) }
        val extremeOption = qualityOptions.first { it.tag == "extreme" }
        fun updateExtremeAvailability(isScreen: Boolean) {
            extremeOption.visibility = if (isScreen) View.VISIBLE else View.GONE
            if (!isScreen && extremeOption.isChecked) {
                qualityOptions.first { it.tag == "ultra" }.isChecked = true
            }
        }
        updateExtremeAvailability(false)
        sourceGroup.setOnCheckedChangeListener { _, checkedId ->
            updateExtremeAvailability(checkedId == screenOption.id)
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(input)
            addView(sourceGroup)
            addView(TextView(requireContext()).apply {
                text = "Quality"
                setPadding(0, padding / 2, 0, 0)
            })
            addView(qualityGroup)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Create live room")
            .setView(content)
            .setPositiveButton("Create") { _, _ ->
                val sourceType = if (sourceGroup.checkedRadioButtonId == screenOption.id) "screen" else "camera"
                val checkedQuality = qualityOptions.firstOrNull { it.id == qualityGroup.checkedRadioButtonId }
                val quality = checkedQuality?.tag as? String ?: "ultra"
                createRoom(input.text?.toString()?.trim().orEmpty(), sourceType, quality)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createRoom(title: String, sourceType: String, quality: String) {
        val token = SyncTuneSession.token ?: return
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.createLiveRoom(
                    LiveRoomCreate(
                        title = title.ifBlank { null },
                        source_type = sourceType,
                        quality = quality,
                    ),
                    token,
                )
            }
                .onSuccess { joinInfo ->
                    setLoading(false)
                    if (joinInfo.livekit_token == null) {
                        showMessage("Room created but LiveKit not configured on server yet")
                        loadRooms()
                    } else {
                        startActivity(LiveRoomActivity.intent(
                            requireContext(),
                            url = joinInfo.livekit_url,
                            token = joinInfo.livekit_token,
                            roomId = joinInfo.id,
                            title = joinInfo.title,
                            isHost = true,
                            sourceType = joinInfo.source_type,
                            quality = joinInfo.quality,
                        ))
                    }
                }
                .onFailure {
                    setLoading(false)
                    showMessage("Failed to create live room: ${it.message}")
                }
        }
    }

    private fun joinRoom(room: LiveRoomInfo) {
        val token = SyncTuneSession.token ?: return
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.joinLiveRoom(room.id, token) }
                .onSuccess { joinInfo ->
                    setLoading(false)
                    if (joinInfo.livekit_token == null) {
                        showMessage("LiveKit not configured on server; can't enter room yet")
                    } else {
                        startActivity(LiveRoomActivity.intent(
                            requireContext(),
                            url = joinInfo.livekit_url,
                            token = joinInfo.livekit_token,
                            roomId = joinInfo.id,
                            title = joinInfo.title,
                            isHost = joinInfo.publish_allowed,
                            sourceType = joinInfo.source_type,
                            quality = joinInfo.quality,
                        ))
                    }
                }
                .onFailure {
                    setLoading(false)
                    showMessage("Failed to join room: ${it.message}")
                }
        }
    }

    private fun endRoom(room: LiveRoomInfo) {
        val token = SyncTuneSession.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.endLiveRoom(room.id, token) }
                .onSuccess {
                    showMessage("Live room ended")
                    loadRooms()
                }
                .onFailure { showMessage("Failed to end room: ${it.message}") }
        }
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        createButton.isEnabled = !loading
        refreshButton.isEnabled = !loading
    }

    private fun showMessage(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }
}

private class LiveRoomAdapter : RecyclerView.Adapter<LiveRoomAdapter.VH>() {
    var onJoin: ((LiveRoomInfo) -> Unit)? = null
    var onEnd: ((LiveRoomInfo) -> Unit)? = null
    private val items = mutableListOf<LiveRoomInfo>()

    fun submitList(rooms: List<LiveRoomInfo>) {
        items.clear()
        items.addAll(rooms)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_live_room, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.live_room_title)
        private val meta: TextView = itemView.findViewById(R.id.live_room_meta)
        private val join: MaterialButton = itemView.findViewById(R.id.live_room_join)
        private val end: MaterialButton = itemView.findViewById(R.id.live_room_end)

        fun bind(room: LiveRoomInfo) {
            title.text = room.title
            meta.text = "${room.owner_nickname ?: "Unknown"} - ${room.source_type} - ${room.quality}"
            join.setOnClickListener { onJoin?.invoke(room) }
            val ownedByMe = room.owner_id == SyncTuneSession.userId
            end.visibility = if (ownedByMe) View.VISIBLE else View.GONE
            end.setOnClickListener { onEnd?.invoke(room) }
        }
    }
}
