package code.name.monkey.retromusic.activities

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class CloudMusicActivity : AppCompatActivity() {
    companion object {
        fun intent(context: Context) = Intent(context, CloudMusicActivity::class.java)
    }

    private val api: SyncTuneApi by inject()
    private lateinit var statusView: TextView
    private lateinit var nowPlayingView: TextView
    private lateinit var playPauseButton: MaterialButton
    private lateinit var adapter: CloudTrackAdapter
    private var mediaPlayer: MediaPlayer? = null
    private var currentTrack: CloudTrackInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud_music)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.cloud_music_toolbar)
            .setNavigationOnClickListener { finish() }
        statusView = findViewById(R.id.cloud_music_status)
        nowPlayingView = findViewById(R.id.cloud_music_now_playing)
        playPauseButton = findViewById(R.id.cloud_music_play_pause)

        adapter = CloudTrackAdapter { playTrack(it) }
        findViewById<RecyclerView>(R.id.cloud_music_list).apply {
            layoutManager = LinearLayoutManager(this@CloudMusicActivity)
            adapter = this@CloudMusicActivity.adapter
        }

        findViewById<MaterialButton>(R.id.cloud_music_refresh).setOnClickListener { loadTracks() }
        playPauseButton.setOnClickListener { togglePlayback() }
        findViewById<MaterialButton>(R.id.cloud_music_stop).setOnClickListener { stopPlayback() }

        loadTracks()
    }

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
                val tracks = api.listCloudTracks(token)
                adapter.submitList(tracks)
                statusView.text = if (tracks.isEmpty()) {
                    getString(R.string.familyhub_cloud_music_empty)
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
