package code.name.monkey.retromusic.activities

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.CloudTrackInfo
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SyncTuneApi
import code.name.monkey.retromusic.SyncTuneConfig
import code.name.monkey.retromusic.SyncTuneSession
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import java.io.IOException
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.koin.android.ext.android.inject

class CloudMusicActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_MODE = "extra_mode"
        const val MODE_ALL = "all"
        const val MODE_LAST_ADDED = "last_added"
        const val MODE_MOST_PLAYED = "most_played"
        const val MODE_HISTORY = "history"

        fun intent(context: Context, mode: String = MODE_ALL) =
            Intent(context, CloudMusicActivity::class.java).putExtra(EXTRA_MODE, mode)

        private val ALLOWED_UPLOAD_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "ogg", "aac")
    }

    private val pickAudioLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { uploadTrack(it) }
        }

    private val api: SyncTuneApi by inject()
    private lateinit var statusView: TextView
    private lateinit var nowPlayingView: TextView
    private lateinit var playPauseButton: MaterialButton
    private lateinit var adapter: CloudTrackAdapter
    private var mediaPlayer: MediaPlayer? = null
    private var currentTrack: CloudTrackInfo? = null
    private val mode: String by lazy { intent.getStringExtra(EXTRA_MODE) ?: MODE_ALL }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud_music)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.cloud_music_toolbar).apply {
            setNavigationOnClickListener { finish() }
            title = titleForMode(mode)
        }
        statusView = findViewById(R.id.cloud_music_status)
        nowPlayingView = findViewById(R.id.cloud_music_now_playing)
        playPauseButton = findViewById(R.id.cloud_music_play_pause)

        adapter = CloudTrackAdapter { playTrack(it) }
        findViewById<RecyclerView>(R.id.cloud_music_list).apply {
            layoutManager = LinearLayoutManager(this@CloudMusicActivity)
            adapter = this@CloudMusicActivity.adapter
        }

        findViewById<MaterialButton>(R.id.cloud_music_refresh).setOnClickListener { loadTracks() }
        findViewById<MaterialButton>(R.id.cloud_music_upload).setOnClickListener { pickAudioLauncher.launch("audio/*") }
        playPauseButton.setOnClickListener { togglePlayback() }
        findViewById<MaterialButton>(R.id.cloud_music_stop).setOnClickListener { stopPlayback() }

        if (mode == MODE_ALL) {
            findViewById<View>(R.id.cloud_music_sections).visibility = View.VISIBLE
            findViewById<MaterialButton>(R.id.cloud_music_section_last_added).setOnClickListener {
                startActivity(intent(this, MODE_LAST_ADDED))
            }
            findViewById<MaterialButton>(R.id.cloud_music_section_most_played).setOnClickListener {
                startActivity(intent(this, MODE_MOST_PLAYED))
            }
            findViewById<MaterialButton>(R.id.cloud_music_section_history).setOnClickListener {
                startActivity(intent(this, MODE_HISTORY))
            }
        }

        loadTracks()
    }

    private fun titleForMode(mode: String): String = getString(
        when (mode) {
            MODE_LAST_ADDED -> R.string.familyhub_cloud_last_added
            MODE_MOST_PLAYED -> R.string.familyhub_cloud_most_played
            MODE_HISTORY -> R.string.familyhub_cloud_history
            else -> R.string.familyhub_cloud_all_songs
        }
    )

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    private fun loadTracks() {
        val token = SyncTuneSession.token
        if (token.isNullOrBlank()) {
            statusView.setText(R.string.familyhub_login_required)
            adapter.submitList(emptyList())
            return
        }

        statusView.setText(R.string.familyhub_cloud_music_loading)
        lifecycleScope.launch {
            try {
                val tracks = when (mode) {
                    MODE_LAST_ADDED -> api.listCloudTracks(token, sort = "recent")
                    MODE_MOST_PLAYED -> api.listCloudTracks(token, sort = "most_played")
                    MODE_HISTORY -> api.listCloudTrackHistory(token)
                    else -> api.listCloudTracks(token)
                }
                adapter.submitList(tracks)
                statusView.text = if (tracks.isEmpty()) {
                    if (mode == MODE_HISTORY) {
                        getString(R.string.familyhub_cloud_history_empty)
                    } else {
                        getString(R.string.familyhub_cloud_music_empty)
                    }
                } else {
                    getString(R.string.familyhub_cloud_music_count, tracks.size)
                }
            } catch (e: Exception) {
                statusView.text = getString(R.string.familyhub_cloud_music_load_failed)
                Snackbar.make(statusView, e.message ?: "Failed to load tracks", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun playTrack(track: CloudTrackInfo) {
        val token = SyncTuneSession.token
        if (token.isNullOrBlank()) {
            Snackbar.make(statusView, R.string.familyhub_login_required, Snackbar.LENGTH_LONG).show()
            return
        }

        releasePlayer()
        currentTrack = track
        nowPlayingView.text = getString(R.string.familyhub_cloud_music_preparing, track.displayTitle())
        playPauseButton.setText(R.string.familyhub_pause)

        val url = SyncTuneConfig.SERVER_BASE_URL.trimEnd('/') + "/stream/" + Uri.encode(track.id)
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnPreparedListener {
                it.start()
                nowPlayingView.text = getString(R.string.familyhub_cloud_music_now_playing, track.displayTitle())
                playPauseButton.setText(R.string.familyhub_pause)
                reportPlay(track.id, token)
            }
            setOnCompletionListener {
                nowPlayingView.setText(R.string.familyhub_cloud_music_idle)
                playPauseButton.setText(R.string.familyhub_play)
            }
            setOnErrorListener { _, _, _ ->
                nowPlayingView.setText(R.string.familyhub_cloud_music_play_failed)
                playPauseButton.setText(R.string.familyhub_play)
                true
            }
            setDataSource(this@CloudMusicActivity, Uri.parse(url), mapOf("X-Token" to token))
            prepareAsync()
        }
    }

    private fun uploadTrack(uri: Uri) {
        val token = SyncTuneSession.token
        if (token.isNullOrBlank()) {
            Snackbar.make(statusView, R.string.familyhub_login_required, Snackbar.LENGTH_LONG).show()
            return
        }

        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "upload"
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension !in ALLOWED_UPLOAD_EXTENSIONS) {
            Snackbar.make(statusView, R.string.familyhub_cloud_upload_unsupported_type, Snackbar.LENGTH_LONG).show()
            return
        }

        statusView.text = getString(R.string.familyhub_cloud_uploading, displayName)
        lifecycleScope.launch {
            try {
                val mediaType = (contentResolver.getType(uri) ?: "audio/*").toMediaTypeOrNull()
                val body = ContentUriRequestBody(contentResolver, uri, mediaType)
                val part = MultipartBody.Part.createFormData("file", displayName, body)
                api.uploadCloudTrack(part, token)
                Snackbar.make(
                    statusView,
                    getString(R.string.familyhub_cloud_upload_success, displayName),
                    Snackbar.LENGTH_LONG,
                ).show()
                loadTracks()
            } catch (e: Exception) {
                statusView.text = getString(R.string.familyhub_cloud_upload_failed)
                Snackbar.make(statusView, e.message ?: getString(R.string.familyhub_cloud_upload_failed), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }

    private fun reportPlay(trackId: String, token: String) {
        lifecycleScope.launch {
            try {
                api.reportCloudTrackPlay(trackId, token)
            } catch (_: Exception) {
                // best-effort play-count/history tracking; ignore failures
            }
        }
    }

    private fun togglePlayback() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            playPauseButton.setText(R.string.familyhub_play)
        } else {
            player.start()
            playPauseButton.setText(R.string.familyhub_pause)
        }
    }

    private fun stopPlayback() {
        releasePlayer()
        currentTrack = null
        nowPlayingView.setText(R.string.familyhub_cloud_music_idle)
        playPauseButton.setText(R.string.familyhub_play)
    }

    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun CloudTrackInfo.displayTitle(): String = title.ifBlank { id }

    private class ContentUriRequestBody(
        private val contentResolver: ContentResolver,
        private val uri: Uri,
        private val mediaType: MediaType?,
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long = try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            -1L
        }

        override fun writeTo(sink: BufferedSink) {
            val input = contentResolver.openInputStream(uri) ?: throw IOException("Unable to open $uri")
            input.use { stream -> sink.writeAll(stream.source()) }
        }
    }

    private class CloudTrackAdapter(
        private val onClick: (CloudTrackInfo) -> Unit,
    ) : RecyclerView.Adapter<CloudTrackViewHolder>() {
        private val items = mutableListOf<CloudTrackInfo>()

        fun submitList(tracks: List<CloudTrackInfo>) {
            items.clear()
            items.addAll(tracks)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CloudTrackViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_cloud_track, parent, false)
            return CloudTrackViewHolder(view, onClick)
        }

        override fun onBindViewHolder(holder: CloudTrackViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private class CloudTrackViewHolder(
        itemView: View,
        private val onClick: (CloudTrackInfo) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.cloud_track_title)
        private val subtitleView: TextView = itemView.findViewById(R.id.cloud_track_subtitle)

        fun bind(track: CloudTrackInfo) {
            titleView.text = track.title.ifBlank { track.id }
            subtitleView.text = listOfNotNull(
                track.artist?.takeIf { it.isNotBlank() },
                track.duration_ms?.let { formatDuration(it) },
                track.source?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { itemView.context.getString(R.string.familyhub_cloud_music_track) }
            itemView.setOnClickListener { onClick(track) }
        }

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
    }
}
