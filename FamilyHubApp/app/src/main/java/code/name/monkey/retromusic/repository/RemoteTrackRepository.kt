package code.name.monkey.retromusic.repository

import android.database.Cursor
import android.util.Log
import code.name.monkey.retromusic.model.Song
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class RemoteTrackRepository(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String,
) : SongRepository {

    private val TAG = "RemoteTrackRepo"

    private fun fetchAll(): List<Song> {
        val url = "$baseUrl/tracks"
        Log.d(TAG, "fetchAll: GET $url")
        val request = Request.Builder().url(url).build()
        return try {
            val response = okHttpClient.newCall(request).execute()
            val body = response.use { it.body?.string() }
            if (body.isNullOrEmpty()) {
                Log.w(TAG, "fetchAll: empty body, code=${response.code}")
                return emptyList()
            }
            val songs = parseTracks(body)
            Log.i(TAG, "fetchAll: loaded ${songs.size} tracks from server")
            songs
        } catch (e: Exception) {
            Log.e(TAG, "fetchAll: FAILED url=$url error=${e::class.simpleName}: ${e.message}")
            emptyList()
        }
    }

    private fun parseTracks(json: String): List<Song> {
        return try {
            val array = JSONArray(json)
            val result = mutableListOf<Song>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val serverId = obj.getString("id")
                val stableId = serverId.hashCode().toLong().let { if (it < 0) -it else it }
                result.add(
                    Song(
                        id = stableId,
                        title = obj.getString("title"),
                        trackNumber = 0,
                        year = 0,
                        duration = if (obj.isNull("duration_ms")) 0L else obj.getLong("duration_ms"),
                        data = "$baseUrl/stream/$serverId",
                        dateModified = 0,
                        albumId = 0,
                        albumName = "",
                        artistId = 0,
                        artistName = if (obj.isNull("artist")) "" else obj.getString("artist"),
                        composer = null,
                        albumArtist = null,
                    )
                )
            }
            Log.d(TAG, "parseTracks: parsed ${result.size} songs")
            result
        } catch (e: Exception) {
            Log.e(TAG, "parseTracks: parse error ${e.message}")
            emptyList()
        }
    }

    override fun songs(): List<Song> = fetchAll()

    override fun songs(query: String): List<Song> =
        fetchAll().filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artistName.contains(query, ignoreCase = true)
        }

    override fun song(songId: Long): Song =
        fetchAll().find { it.id == songId } ?: Song.emptySong

    // MediaStore Cursor 方法不适用于远端模式
    override fun songs(cursor: Cursor?): List<Song> = emptyList()
    override fun sortedSongs(cursor: Cursor?): List<Song> = emptyList()
    override fun songsByFilePath(filePath: String, ignoreBlacklist: Boolean): List<Song> = emptyList()
    override fun song(cursor: Cursor?): Song = Song.emptySong
}
