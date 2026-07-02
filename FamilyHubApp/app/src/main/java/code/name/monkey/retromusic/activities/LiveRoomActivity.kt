package code.name.monkey.retromusic.activities

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SyncTuneApi
import code.name.monkey.retromusic.LiveRoomQualityUpdate
import code.name.monkey.retromusic.SyncTuneSession
import com.google.android.material.button.MaterialButton
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

private data class LiveQualityProfile(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
) {
    companion object {
        fun forRoom(sourceType: String, quality: String): LiveQualityProfile {
            val normalized = if (quality == "original") "high" else quality
            return if (sourceType == "screen") {
                when (normalized) {
                    "ultra" -> LiveQualityProfile("Screen Ultra 1440p30", 2560, 1440, 30, 10_000_000)
                    "high" -> LiveQualityProfile("Screen High 1080p60", 1920, 1080, 60, 8_000_000)
                    "hd" -> LiveQualityProfile("Screen HD 1080p30", 1920, 1080, 30, 5_000_000)
                    "standard" -> LiveQualityProfile("Screen Standard 720p30", 1280, 720, 30, 2_500_000)
                    "smooth" -> LiveQualityProfile("Screen Smooth 720p15", 1280, 720, 15, 1_500_000)
                    else -> LiveQualityProfile("Screen Ultra 1440p30", 2560, 1440, 30, 10_000_000)
                }
            } else {
                when (normalized) {
                    "ultra" -> LiveQualityProfile("Camera Ultra 1080p60", 1920, 1080, 60, 8_000_000)
                    "high" -> LiveQualityProfile("Camera High 1080p30", 1920, 1080, 30, 5_000_000)
                    "hd" -> LiveQualityProfile("Camera HD 720p60", 1280, 720, 60, 4_000_000)
                    "standard" -> LiveQualityProfile("Camera Standard 720p30", 1280, 720, 30, 2_000_000)
                    "smooth" -> LiveQualityProfile("Camera Smooth 480p24", 854, 480, 24, 900_000)
                    else -> LiveQualityProfile("Camera Ultra 1080p60", 1920, 1080, 60, 8_000_000)
                }
            }
        }
    }
}

private val liveQualityOptions = listOf(
    "ultra" to "Ultra",
    "high" to "High",
    "hd" to "HD",
    "standard" to "Standard",
    "smooth" to "Smooth",
)

class LiveRoomActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LiveRoomActivity"

        const val EXTRA_URL = "livekit_url"
        const val EXTRA_TOKEN = "livekit_token"
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_TITLE = "room_title"
        const val EXTRA_IS_HOST = "is_host"
        const val EXTRA_SOURCE_TYPE = "source_type"
        const val EXTRA_QUALITY = "quality"

        fun intent(
            context: Context,
            url: String,
            token: String,
            roomId: String,
            title: String,
            isHost: Boolean,
            sourceType: String,
            quality: String,
        ): Intent = Intent(context, LiveRoomActivity::class.java).apply {
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_TOKEN, token)
            putExtra(EXTRA_ROOM_ID, roomId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_IS_HOST, isHost)
            putExtra(EXTRA_SOURCE_TYPE, sourceType)
            putExtra(EXTRA_QUALITY, quality)
        }
    }

    private val api: SyncTuneApi by inject()

    private lateinit var room: Room
    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var localPreviewView: SurfaceViewRenderer
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var hostControls: View
    private lateinit var btnMic: MaterialButton
    private lateinit var btnCam: MaterialButton
    private lateinit var btnQuality: MaterialButton
    private lateinit var btnFlip: MaterialButton
    private lateinit var btnEnd: MaterialButton

    private var isHost = false
    private var roomId = ""
    private var sourceType = "camera"
    private var quality = "ultra"
    private var micEnabled = true
    private var videoEnabled = true
    private var localVideoTrack: LocalVideoTrack? = null
    private var screenShareIntent: Intent? = null
    private var heartbeatJob: Job? = null
    private var roomStatusJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            connectToRoom()
        } else {
            statusText.text = "Required permission denied"
            btnEnd.postDelayed({ finish() }, 2000)
        }
    }

    private val screenShareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            screenShareIntent = result.data
            lifecycleScope.launch { startScreenShare(result.data!!) }
        } else {
            statusText.text = "Screen share permission denied"
            btnEnd.postDelayed({ endAndLeave() }, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_live_room)

        isHost = intent.getBooleanExtra(EXTRA_IS_HOST, false)
        roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: ""
        sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE) ?: "camera"
        quality = intent.getStringExtra(EXTRA_QUALITY) ?: "ultra"

        remoteVideoView = findViewById(R.id.live_remote_video)
        localPreviewView = findViewById(R.id.live_local_preview)
        statusText = findViewById(R.id.live_status)
        titleText = findViewById(R.id.live_title)
        hostControls = findViewById(R.id.live_host_controls)
        btnMic = findViewById(R.id.btn_mic)
        btnCam = findViewById(R.id.btn_cam)
        btnQuality = findViewById(R.id.btn_quality)
        btnFlip = findViewById(R.id.btn_flip_cam)
        btnEnd = findViewById(R.id.btn_end)

        titleText.text = intent.getStringExtra(EXTRA_TITLE) ?: "Live"

        val hostVisibility = if (isHost) View.VISIBLE else View.GONE
        hostControls.visibility = hostVisibility
        btnMic.visibility = hostVisibility
        btnCam.visibility = hostVisibility
        btnQuality.visibility = hostVisibility
        btnFlip.visibility = if (isHost && sourceType == "camera") View.VISIBLE else View.GONE
        btnCam.text = if (sourceType == "screen") "Stop Share" else "Cam Off"
        updateQualityButtonText()

        room = LiveKit.create(applicationContext)
        room.initVideoRenderer(remoteVideoView)
        room.initVideoRenderer(localPreviewView)
        applyLiveQuality()

        btnMic.setOnClickListener { toggleMic() }
        btnCam.setOnClickListener { toggleCam() }
        btnQuality.setOnClickListener { showQualityDialog() }
        btnFlip.setOnClickListener { flipCam() }
        btnEnd.setOnClickListener { endAndLeave() }

        if (isHost) {
            val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val permissions = if (sourceType == "screen") {
                arrayOf(Manifest.permission.RECORD_AUDIO)
            } else {
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            }
            if ((sourceType == "screen" && hasMic) || (sourceType == "camera" && hasCam && hasMic)) {
                connectToRoom()
            } else {
                permissionLauncher.launch(permissions)
            }
        } else {
            connectToRoom()
        }
    }

    private fun connectToRoom() {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        statusText.text = "Connecting..."

        lifecycleScope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        statusText.text = if (isHost) "Live" else "Watching"
                        if (isHost) {
                            startHeartbeat()
                            lifecycleScope.launch { startHostTracks() }
                        } else {
                            startRoomStatusPolling()
                        }
                    }
                    is RoomEvent.Disconnected -> {
                        statusText.text = "Disconnected"
                        finish()
                    }
                    is RoomEvent.TrackSubscribed -> {
                        val track = event.track
                        if (track is RemoteVideoTrack) {
                            if (!isHost) {
                                remoteVideoView.visibility = View.VISIBLE
                                track.addRenderer(remoteVideoView)
                            }
                        }
                    }
                    is RoomEvent.TrackPublished -> {
                        val track = event.publication.track
                        if (isHost && track is LocalVideoTrack && localVideoTrack == null) {
                            attachLocalPreview(track)
                        }
                    }
                    is RoomEvent.TrackUnsubscribed -> {
                        val track = event.track
                        if (track is RemoteVideoTrack) {
                            track.removeRenderer(remoteVideoView)
                            remoteVideoView.visibility = View.INVISIBLE
                        }
                    }
                    else -> {}
                }
            }
        }

        lifecycleScope.launch {
            runCatching { room.connect(url, token) }
                .onFailure { statusText.text = "Connection failed: ${it.message}" }
        }
    }

    private suspend fun startHostTracks() {
        if (sourceType == "screen") {
            startHeartbeat()
            requestScreenShare()
            startMicrophone()
            return
        }

        statusText.text = "Starting camera..."
        runCatching { room.localParticipant.setCameraEnabled(true) }
            .onFailure {
                Log.e(TAG, "startHostTracks: camera failed", it)
                statusText.text = "Camera failed: ${it.message}"
                return
            }
        val cameraTrack = waitForPublishedVideoTrack(Track.Source.CAMERA)
        if (cameraTrack == null) {
            Log.w(TAG, "startHostTracks: camera enabled but no local camera track found")
            statusText.text = "Camera started, waiting for preview..."
        } else {
            attachLocalPreview(cameraTrack)
        }

        startMicrophone()
        statusText.text = "Live"
    }

    private fun applyLiveQuality() {
        val profile = LiveQualityProfile.forRoom(sourceType, quality)
        room.videoTrackCaptureDefaults = LocalVideoTrackOptions(
            isScreencast = sourceType == "screen",
            captureParams = VideoCaptureParameter(
                profile.width,
                profile.height,
                profile.fps,
            ),
        )
        room.videoTrackPublishDefaults = VideoTrackPublishDefaults(
            videoEncoding = VideoEncoding(profile.bitrate, profile.fps),
            simulcast = false,
        )
        statusText.text = profile.label
        Log.i(TAG, "applyLiveQuality: source=$sourceType quality=$quality profile=$profile")
    }

    private fun requestScreenShare() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusText.text = "Waiting for screen permission..."
        screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private suspend fun startScreenShare(data: Intent) {
        statusText.text = "Starting screen share..."
        runCatching { room.localParticipant.setScreenShareEnabled(true, data) }
            .onFailure {
                Log.e(TAG, "startScreenShare: failed", it)
                statusText.text = "Screen share failed: ${it.message}"
                return
            }
        val screenTrack = waitForPublishedVideoTrack(Track.Source.SCREEN_SHARE)
        if (screenTrack != null) {
            attachLocalPreview(screenTrack)
        }
        statusText.text = "Live"
    }

    private suspend fun startMicrophone() {
        runCatching { room.localParticipant.setMicrophoneEnabled(true) }
            .onFailure { Log.e(TAG, "startMicrophone: failed", it) }
    }

    private fun toggleMic() {
        micEnabled = !micEnabled
        lifecycleScope.launch { room.localParticipant.setMicrophoneEnabled(micEnabled) }
        btnMic.text = if (micEnabled) "Mute" else "Unmute"
    }

    private fun toggleCam() {
        videoEnabled = !videoEnabled
        lifecycleScope.launch {
            if (sourceType == "screen") {
                if (videoEnabled) {
                    screenShareIntent?.let { startScreenShare(it) } ?: requestScreenShare()
                } else {
                    room.localParticipant.setScreenShareEnabled(false, null)
                    remoteVideoView.visibility = View.INVISIBLE
                }
            } else {
                room.localParticipant.setCameraEnabled(videoEnabled)
                if (videoEnabled) attachPublishedCameraTrack()
                remoteVideoView.visibility = if (videoEnabled) View.VISIBLE else View.INVISIBLE
            }
        }
        btnCam.text = if (sourceType == "screen") {
            if (videoEnabled) "Stop Share" else "Share Screen"
        } else {
            if (videoEnabled) "Cam Off" else "Cam On"
        }
    }

    private fun flipCam() {
        localVideoTrack?.switchCamera()
    }

    private fun showQualityDialog() {
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        liveQualityOptions.forEach { (key, label) ->
            val profile = LiveQualityProfile.forRoom(sourceType, key)
            val option = RadioButton(this).apply {
                text = "$label - ${profile.label.substringAfter(' ')}"
                tag = key
                id = View.generateViewId()
                isChecked = key == quality
            }
            group.addView(option)
        }

        AlertDialog.Builder(this)
            .setTitle("Live quality")
            .setView(group)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val selected = group.findViewById<RadioButton>(group.checkedRadioButtonId)
                val selectedQuality = selected?.tag as? String ?: quality
                changeQuality(selectedQuality)
            }
            .show()
    }

    private fun changeQuality(nextQuality: String) {
        if (!isHost || nextQuality == quality) return
        lifecycleScope.launch {
            val token = SyncTuneSession.token
            if (token == null) {
                statusText.text = "Login expired"
                return@launch
            }
            statusText.text = "Changing quality..."
            runCatching {
                api.updateLiveRoomQuality(roomId, LiveRoomQualityUpdate(nextQuality), token)
            }.onSuccess { updated ->
                quality = updated.quality
                updateQualityButtonText()
                applyLiveQuality()
                restartVideoForQuality()
            }.onFailure {
                Log.e(TAG, "changeQuality: failed", it)
                statusText.text = "Quality change failed"
            }
        }
    }

    private suspend fun restartVideoForQuality() {
        if (!videoEnabled) {
            statusText.text = "Quality ready"
            return
        }
        if (sourceType == "screen") {
            val data = screenShareIntent
            if (data == null) {
                requestScreenShare()
                return
            }
            runCatching {
                room.localParticipant.setScreenShareEnabled(false, null)
                delay(300)
                applyLiveQuality()
                room.localParticipant.setScreenShareEnabled(true, data)
            }.onFailure {
                Log.e(TAG, "restartVideoForQuality: screen failed", it)
                statusText.text = "Screen restart failed"
                return
            }
            waitForPublishedVideoTrack(Track.Source.SCREEN_SHARE)?.let { attachLocalPreview(it) }
        } else {
            runCatching {
                room.localParticipant.setCameraEnabled(false)
                delay(300)
                applyLiveQuality()
                room.localParticipant.setCameraEnabled(true)
            }.onFailure {
                Log.e(TAG, "restartVideoForQuality: camera failed", it)
                statusText.text = "Camera restart failed"
                return
            }
            waitForPublishedVideoTrack(Track.Source.CAMERA)?.let { attachLocalPreview(it) }
        }
        statusText.text = "Live"
    }

    private fun updateQualityButtonText() {
        val label = liveQualityOptions.firstOrNull { it.first == quality }?.second ?: "Ultra"
        btnQuality.text = label
    }

    private fun attachLocalPreview(track: LocalVideoTrack) {
        Log.i(TAG, "attachLocalPreview: attaching local video renderer")
        localVideoTrack?.removeRenderer(localPreviewView)
        localVideoTrack?.removeRenderer(remoteVideoView)
        localVideoTrack = track
        if (isHost) {
            remoteVideoView.visibility = if (videoEnabled) View.VISIBLE else View.INVISIBLE
            localPreviewView.visibility = View.GONE
            remoteVideoView.post {
                track.addRenderer(remoteVideoView)
            }
        } else {
            localPreviewView.visibility = if (videoEnabled) View.VISIBLE else View.GONE
            localPreviewView.post {
                track.addRenderer(localPreviewView)
            }
        }
    }

    private fun attachPublishedCameraTrack() {
        val track = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track
        if (track is LocalVideoTrack) {
            attachLocalPreview(track)
        } else {
            Log.w(TAG, "attachPublishedCameraTrack: no local camera track yet")
        }
    }

    private suspend fun waitForPublishedVideoTrack(source: Track.Source): LocalVideoTrack? {
        repeat(20) { attempt ->
            val track = room.localParticipant.getTrackPublication(source)?.track
            if (track is LocalVideoTrack) {
                Log.i(TAG, "waitForPublishedVideoTrack: found $source track on attempt ${attempt + 1}")
                return track
            }
            delay(250)
        }
        return null
    }

    private fun startHeartbeat() {
        if (!isHost || heartbeatJob?.isActive == true) return
        heartbeatJob = lifecycleScope.launch {
            val token = SyncTuneSession.token ?: return@launch
            while (isActive) {
                runCatching { api.heartbeatLiveRoom(roomId, token) }
                delay(30_000)
            }
        }
    }

    private fun startRoomStatusPolling() {
        if (isHost || roomStatusJob?.isActive == true) return
        roomStatusJob = lifecycleScope.launch {
            val token = SyncTuneSession.token ?: return@launch
            while (isActive) {
                delay(3_000)
                val latestRoom = runCatching { api.getLiveRoom(roomId, token) }.getOrNull()
                val roomIsLive = latestRoom?.status == "live"
                if (!roomIsLive) {
                    statusText.text = "Live ended"
                    room.disconnect()
                    finish()
                    return@launch
                }
                if (latestRoom?.quality != null && latestRoom.quality != quality) {
                    quality = latestRoom.quality
                    statusText.text = "Watching"
                }
            }
        }
    }

    private fun endAndLeave() {
        lifecycleScope.launch {
            heartbeatJob?.cancel()
            roomStatusJob?.cancel()
            if (isHost) {
                if (sourceType == "screen") {
                    runCatching { room.localParticipant.setScreenShareEnabled(false, null) }
                }
                SyncTuneSession.token?.let { token ->
                    runCatching { api.endLiveRoom(roomId, token) }
                }
            }
            room.disconnect()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatJob?.cancel()
        roomStatusJob?.cancel()
        room.disconnect()
        localVideoTrack?.removeRenderer(localPreviewView)
        localVideoTrack?.removeRenderer(remoteVideoView)
        if (::remoteVideoView.isInitialized) remoteVideoView.release()
        if (::localPreviewView.isInitialized) localPreviewView.release()
    }
}
