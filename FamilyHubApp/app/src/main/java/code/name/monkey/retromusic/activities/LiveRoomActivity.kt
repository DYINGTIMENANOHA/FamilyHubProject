package code.name.monkey.retromusic.activities

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import android.net.Uri
import android.provider.Settings
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SyncTuneApi
import code.name.monkey.retromusic.LiveRoomQualityUpdate
import code.name.monkey.retromusic.SyncTuneSession
import code.name.monkey.retromusic.BuildConfig
import code.name.monkey.retromusic.live.LiveShareOverlay
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.ScreenAudioCapturer
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioPresets
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import org.koin.android.ext.android.inject

private data class LiveQualityProfile(
    val label: String,
    // For screen capture: width is the long side, height the short side; the SDK swaps
    // them automatically to follow the display orientation.
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
    private lateinit var topBar: View
    private lateinit var bottomControls: View
    private lateinit var hostControls: View
    private lateinit var hostAudioControls: View
    private lateinit var shareAudioLabel: TextView
    private lateinit var shareAudioSlider: Slider
    private lateinit var btnMic: MaterialButton
    private lateinit var btnSysAudio: MaterialButton
    private lateinit var btnCam: MaterialButton
    private lateinit var btnQuality: MaterialButton
    private lateinit var btnFlip: MaterialButton
    private lateinit var btnRemoteAudio: MaterialButton
    private lateinit var btnEnd: MaterialButton

    private var isHost = false
    private var roomId = ""
    private var sourceType = "camera"
    private var quality = "ultra"
    private var micEnabled = true
    private var systemAudioEnabled = true
    private var systemAudioVolume = 1f
    private var remoteAudioMuted = false
    private var viewerChromeVisible = true
    private var videoEnabled = true
    private var localVideoTrack: LocalVideoTrack? = null
    private var remoteAudioTrack: RemoteAudioTrack? = null
    private var heartbeatJob: Job? = null
    private var roomStatusJob: Job? = null
    private var screenAudioCapturer: ScreenAudioCapturer? = null
    private var shareOverlay: LiveShareOverlay? = null
    private var overlayPermissionAsked = false

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

        if (BuildConfig.DEBUG) {
            LiveKit.loggingLevel = LoggingLevel.INFO
        }

        isHost = intent.getBooleanExtra(EXTRA_IS_HOST, false)
        roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: ""
        sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE) ?: "camera"
        quality = intent.getStringExtra(EXTRA_QUALITY) ?: "ultra"

        remoteVideoView = findViewById(R.id.live_remote_video)
        localPreviewView = findViewById(R.id.live_local_preview)
        statusText = findViewById(R.id.live_status)
        titleText = findViewById(R.id.live_title)
        topBar = findViewById(R.id.live_top_bar)
        bottomControls = findViewById(R.id.live_bottom_controls)
        hostControls = findViewById(R.id.live_host_controls)
        hostAudioControls = findViewById(R.id.live_host_audio_controls)
        shareAudioLabel = findViewById(R.id.share_audio_label)
        shareAudioSlider = findViewById(R.id.share_audio_slider)
        btnMic = findViewById(R.id.btn_mic)
        btnSysAudio = findViewById(R.id.btn_sys_audio)
        btnCam = findViewById(R.id.btn_cam)
        btnQuality = findViewById(R.id.btn_quality)
        btnFlip = findViewById(R.id.btn_flip_cam)
        btnRemoteAudio = findViewById(R.id.btn_remote_audio)
        btnEnd = findViewById(R.id.btn_end)

        titleText.text = intent.getStringExtra(EXTRA_TITLE) ?: "Live"

        val hostVisibility = if (isHost) View.VISIBLE else View.GONE
        hostControls.visibility = hostVisibility
        btnMic.visibility = hostVisibility
        btnCam.visibility = hostVisibility
        btnQuality.visibility = hostVisibility
        btnFlip.visibility = if (isHost && sourceType == "camera") View.VISIBLE else View.GONE
        btnSysAudio.visibility = if (isHost && sourceType == "screen" && systemAudioSupported()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        hostAudioControls.visibility = if (isHost && sourceType == "screen" && systemAudioSupported()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        btnRemoteAudio.visibility = if (isHost) View.GONE else View.VISIBLE
        btnCam.text = if (sourceType == "screen") "Stop Share" else "Cam Off"
        btnMic.text = if (micEnabled) "Mic On" else "Mic Off"
        updateSystemAudioUi()
        updateRemoteAudioButtonText()
        updateQualityButtonText()

        // Viewers only play remote audio; route it through the media stream instead of the
        // voice-call path so music/system audio isn't degraded by call processing.
        val overrides = if (isHost) {
            LiveKitOverrides()
        } else {
            LiveKitOverrides(audioOptions = AudioOptions(audioOutputType = AudioType.MediaAudioType()))
        }
        room = LiveKit.create(applicationContext, overrides = overrides)
        room.initVideoRenderer(remoteVideoView)
        room.initVideoRenderer(localPreviewView)
        // Letterbox instead of cropping so landscape streams are fully visible on a portrait
        // screen (and vice versa).
        remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        localPreviewView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        applyLiveQuality()

        btnMic.setOnClickListener { toggleMic() }
        btnSysAudio.setOnClickListener { toggleSystemAudio() }
        btnCam.setOnClickListener { toggleCam() }
        btnQuality.setOnClickListener { showQualityDialog() }
        btnFlip.setOnClickListener { flipCam() }
        btnRemoteAudio.setOnClickListener { toggleRemoteAudio() }
        btnEnd.setOnClickListener { endAndLeave() }
        shareAudioSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                systemAudioVolume = value / 100f
                systemAudioEnabled = systemAudioVolume > 0f
                applySystemAudioGain()
                updateSystemAudioUi(syncSlider = false)
                shareOverlay?.updateState(micEnabled, systemAudioEnabled)
            }
        }
        if (!isHost) {
            remoteVideoView.setOnClickListener { toggleViewerChrome() }
        }

        if (isHost) {
            val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val permissions = if (sourceType == "screen") {
                arrayOf(Manifest.permission.RECORD_AUDIO)
            } else {
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            }
            if (sourceType == "screen") {
                maybeAskOverlayPermission()
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

    private fun maybeAskOverlayPermission() {
        if (Settings.canDrawOverlays(this) || overlayPermissionAsked) return
        overlayPermissionAsked = true
        AlertDialog.Builder(this)
            .setTitle("Floating controls")
            .setMessage("Allow the app to display over other apps to get a floating LIVE button for mic/sound toggles while sharing your screen.")
            .setPositiveButton("Grant") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    private fun showShareOverlay() {
        if (!isHost || sourceType != "screen") return
        if (!Settings.canDrawOverlays(this)) return
        if (shareOverlay?.isShowing() == true) return
        val overlay = shareOverlay ?: LiveShareOverlay(
            applicationContext,
            object : LiveShareOverlay.Listener {
                override fun onToggleMic() = runOnUiThread { toggleMic() }
                override fun onToggleSystemAudio() = runOnUiThread { toggleSystemAudio() }
                override fun onOpenRoom() {
                    startActivity(
                        Intent(this@LiveRoomActivity, LiveRoomActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    )
                }
                override fun onEndLive() = runOnUiThread { endAndLeave() }
            },
        ).also { shareOverlay = it }
        overlay.show(
            showSystemAudioToggle = systemAudioSupported(),
            micOn = micEnabled,
            soundOn = systemAudioEnabled,
        )
    }

    override fun onResume() {
        super.onResume()
        // If the user granted the overlay permission from settings mid-share, attach now.
        if (isHost && sourceType == "screen" && videoEnabled &&
            room.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track != null
        ) {
            showShareOverlay()
        }
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    private fun systemAudioSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun toggleViewerChrome() {
        if (isHost) return
        viewerChromeVisible = !viewerChromeVisible
        val visibility = if (viewerChromeVisible) View.VISIBLE else View.GONE
        topBar.visibility = visibility
        bottomControls.visibility = visibility
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
                        } else if (track is RemoteAudioTrack && !isHost) {
                            remoteAudioTrack = track
                            applyRemoteAudioMuteState()
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
                        } else if (track is RemoteAudioTrack && track == remoteAudioTrack) {
                            remoteAudioTrack = null
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
        val captureOptions = LocalVideoTrackOptions(
            isScreencast = sourceType == "screen",
            captureParams = VideoCaptureParameter(
                profile.width,
                profile.height,
                profile.fps,
            ),
        )
        val publishOptions = VideoTrackPublishDefaults(
            videoEncoding = VideoEncoding(profile.bitrate, profile.fps),
            simulcast = false,
        )
        if (sourceType == "screen") {
            room.screenShareTrackCaptureDefaults = captureOptions
            room.screenShareTrackPublishDefaults = publishOptions
            // Screen-share audio carries app/system sound (music, video, games): turn off the
            // voice-call processing chain that mangles non-speech audio, raise the Opus
            // bitrate, and keep encoding continuous (DTX gates quiet passages of music).
            room.audioTrackCaptureDefaults = LocalAudioTrackOptions(
                noiseSuppression = false,
                echoCancellation = false,
                autoGainControl = false,
                highPassFilter = false,
                typingNoiseDetection = false,
            )
            room.audioTrackPublishDefaults = AudioTrackPublishDefaults(
                audioBitrate = AudioPresets.MUSIC_HIGH_QUALITY_STEREO.maxBitrate,
                dtx = false,
            )
        } else {
            room.videoTrackCaptureDefaults = captureOptions
            room.videoTrackPublishDefaults = publishOptions
        }
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
        applyLiveQuality()
        runCatching { room.localParticipant.setScreenShareEnabled(true, ScreenCaptureParams(data)) }
            .onFailure {
                Log.e(TAG, "startScreenShare: failed", it)
                statusText.text = "Screen share failed: ${it.message}"
                return
            }
        val screenTrack = waitForPublishedVideoTrack(Track.Source.SCREEN_SHARE)
        if (screenTrack != null) {
            attachLocalPreview(screenTrack)
        }
        startMicrophone()
        waitForPublishedAudioTrack(Track.Source.MICROPHONE)
        applyMicMuteState()
        attachSystemAudioWhenReady()
        showShareOverlay()
        statusText.text = "Live"
    }

    private suspend fun startMicrophone() {
        runCatching { room.localParticipant.setMicrophoneEnabled(true) }
            .onFailure { Log.e(TAG, "startMicrophone: failed", it) }
    }

    private suspend fun waitForPublishedAudioTrack(source: Track.Source): LocalAudioTrack? {
        repeat(20) { attempt ->
            val track = room.localParticipant.getTrackPublication(source)?.track
            if (track is LocalAudioTrack) {
                Log.i(TAG, "waitForPublishedAudioTrack: found $source track on attempt ${attempt + 1}")
                return track
            }
            delay(250)
        }
        Log.w(TAG, "waitForPublishedAudioTrack: timed out waiting for $source")
        return null
    }

    /**
     * Mixes the device's own playback audio into the published audio track using the
     * screen share's MediaProjection (Android 10+). The mic track stays published as the
     * carrier; [toggleMic] mutes only the mic input at the audio-device level while
     * [toggleSystemAudio] adjusts the capturer gain, so the two are independent.
     */
    private fun attachSystemAudio() {
        if (sourceType != "screen" || !systemAudioSupported()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "attachSystemAudio: RECORD_AUDIO permission missing")
            return
        }
        val screenTrack = room.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track
        val audioTrack = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack
        if (screenTrack == null || audioTrack == null) {
            Log.w(TAG, "attachSystemAudio: tracks not ready (screen=$screenTrack audio=$audioTrack)")
            statusText.text = "System sound unavailable"
            return
        }
        detachSystemAudio()
        val capturer = ScreenAudioCapturer.createFromScreenShareTrack(screenTrack)
        if (capturer == null) {
            Log.w(TAG, "attachSystemAudio: failed to create ScreenAudioCapturer")
            statusText.text = "System sound unavailable"
            return
        }
        capturer.gain = currentSystemAudioGain()
        audioTrack.setAudioBufferCallback(capturer)
        screenAudioCapturer = capturer
        Log.i(TAG, "attachSystemAudio: system audio capture attached")
    }

    private suspend fun attachSystemAudioWhenReady() {
        if (sourceType != "screen" || !systemAudioSupported()) return
        repeat(20) { attempt ->
            val screenTrack = room.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track
            val audioTrack = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track
            if (screenTrack != null && audioTrack is LocalAudioTrack) {
                attachSystemAudio()
                if (screenAudioCapturer != null) {
                    return
                }
            }
            Log.w(TAG, "attachSystemAudioWhenReady: tracks not ready on attempt ${attempt + 1}")
            delay(250)
        }
        attachSystemAudio()
    }

    private fun detachSystemAudio() {
        if (!systemAudioSupported()) return
        val capturer = screenAudioCapturer ?: return
        screenAudioCapturer = null
        runCatching {
            (room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack)
                ?.setAudioBufferCallback(null)
        }
        runCatching { capturer.releaseAudioResources() }
    }

    private fun toggleMic() {
        micEnabled = !micEnabled
        if (sourceType == "screen") {
            // Don't mute the track itself: it carries system audio too. Mute only the
            // microphone input; the mix callback keeps running with silent mic data.
            applyMicMuteState()
        } else {
            lifecycleScope.launch { room.localParticipant.setMicrophoneEnabled(micEnabled) }
        }
        btnMic.text = if (micEnabled) "Mic On" else "Mic Off"
        shareOverlay?.updateState(micEnabled, systemAudioEnabled)
    }

    private fun applyMicMuteState() {
        val adm = room.lkObjects.audioDeviceModule as? JavaAudioDeviceModule
        if (adm == null) {
            Log.w(TAG, "applyMicMuteState: JavaAudioDeviceModule unavailable")
            return
        }
        adm.setMicrophoneMute(!micEnabled)
    }

    private fun toggleSystemAudio() {
        if (!systemAudioSupported()) return
        systemAudioEnabled = !systemAudioEnabled
        if (systemAudioEnabled && systemAudioVolume <= 0f) {
            systemAudioVolume = 1f
        }
        applySystemAudioGain()
        updateSystemAudioUi()
        shareOverlay?.updateState(micEnabled, systemAudioEnabled)
    }

    private fun currentSystemAudioGain(): Float = if (systemAudioEnabled) systemAudioVolume else 0f

    private fun applySystemAudioGain() {
        screenAudioCapturer?.gain = currentSystemAudioGain()
    }

    private fun updateSystemAudioUi(syncSlider: Boolean = true) {
        btnSysAudio.text = if (systemAudioEnabled) "Sound On" else "Sound Off"
        val percent = (currentSystemAudioGain() * 100).toInt()
        shareAudioLabel.text = "Share $percent%"
        if (syncSlider && shareAudioSlider.value.toInt() != (systemAudioVolume * 100).toInt()) {
            shareAudioSlider.value = systemAudioVolume * 100
        }
    }

    private fun toggleRemoteAudio() {
        remoteAudioMuted = !remoteAudioMuted
        applyRemoteAudioMuteState()
        updateRemoteAudioButtonText()
    }

    private fun applyRemoteAudioMuteState() {
        remoteAudioTrack?.setVolume(if (remoteAudioMuted) 0.0 else 1.0)
    }

    private fun updateRemoteAudioButtonText() {
        btnRemoteAudio.text = if (remoteAudioMuted) "Sound Off" else "Sound On"
    }

    private fun toggleCam() {
        videoEnabled = !videoEnabled
        lifecycleScope.launch {
            if (sourceType == "screen") {
                if (videoEnabled) {
                    // MediaProjection grants are single-use on Android 14+, so resuming
                    // always goes through a fresh permission request.
                    requestScreenShare()
                } else {
                    detachSystemAudio()
                    runCatching { room.localParticipant.setScreenShareEnabled(false) }
                    remoteVideoView.visibility = View.INVISIBLE
                    shareOverlay?.dismiss()
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
            // The projection token can't be reused, so restarting at the new quality
            // requires asking for the screen again.
            detachSystemAudio()
            runCatching { room.localParticipant.setScreenShareEnabled(false) }
            requestScreenShare()
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
            statusText.text = "Live"
        }
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
            shareOverlay?.dismiss()
            heartbeatJob?.cancel()
            roomStatusJob?.cancel()
            if (isHost) {
                if (sourceType == "screen") {
                    detachSystemAudio()
                    runCatching { room.localParticipant.setScreenShareEnabled(false) }
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
        shareOverlay?.dismiss()
        shareOverlay = null
        heartbeatJob?.cancel()
        roomStatusJob?.cancel()
        detachSystemAudio()
        room.disconnect()
        localVideoTrack?.removeRenderer(localPreviewView)
        localVideoTrack?.removeRenderer(remoteVideoView)
        if (::remoteVideoView.isInitialized) remoteVideoView.release()
        if (::localPreviewView.isInitialized) localPreviewView.release()
    }
}
