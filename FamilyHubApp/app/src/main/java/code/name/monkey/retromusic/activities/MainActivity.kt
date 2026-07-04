/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import androidx.navigation.ui.setupWithNavController
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.RoomRole
import code.name.monkey.retromusic.SyncController
import code.name.monkey.retromusic.SyncTuneApi
import code.name.monkey.retromusic.SyncTuneSession
import code.name.monkey.retromusic.SyncTuneWebSocketManager
import code.name.monkey.retromusic.fragments.room.RoomControlSheet
import code.name.monkey.retromusic.activities.base.AbsCastActivity
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.SearchQueryHelper.getSongs
import code.name.monkey.retromusic.interfaces.IScrollHelper
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.repository.PlaylistSongsLoader
import code.name.monkey.retromusic.service.MusicService
import code.name.monkey.retromusic.util.AppRater
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.logE
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject

class MainActivity : AbsCastActivity() {
    companion object {
        const val TAG = "MainActivity"
        const val EXPAND_PANEL = "expand_panel"
        private const val LOG_TAG = "SyncTuneMain"
    }

    private val wsManager: SyncTuneWebSocketManager by inject()
    private val api: SyncTuneApi by inject()
    private val syncController: SyncController by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Redirect to login if no session
        if (!SyncTuneSession.isLoggedIn) {
            Log.i(LOG_TAG, "onCreate: no session, going to LoginActivity")
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        Log.i(LOG_TAG, "onCreate: user=${SyncTuneSession.nickname}")
        validateSession()
        setTaskDescriptionColorAuto()
        hideStatusBar()
        updateTabs()
        AppRater.appLaunched(this)

        setupNavigationController()

        // Connect WebSocket for presence updates
        SyncTuneSession.token?.let { token ->
            Log.i(LOG_TAG, "connecting WebSocket")
            wsManager.connect(token)
        }

        // Start sync controller (idempotent 闂?safe to call on config change)
        syncController.start()
        Log.i(LOG_TAG, "SyncController started")

        WhatsNewFragment.showChangeLog(this)
    }

    private fun validateSession() {
        val token = SyncTuneSession.token ?: return
        lifecycleScope.launch {
            runCatching { api.me(token) }
                .onSuccess { user ->
                    Log.i(LOG_TAG, "session valid: nickname=${user.nickname} maxDevices=${user.max_devices}")
                }
                .onFailure { error ->
                    Log.w(LOG_TAG, "session invalid, returning to login: ${error.message}")
                    SyncTuneSession.clear()
                    wsManager.disconnect()
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.i(LOG_TAG, "onDestroy: disconnecting WebSocket")
        wsManager.disconnect()
    }

    private var roomMenuItem: MenuItem? = null

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val result = super.onCreateOptionsMenu(menu)
        roomMenuItem = menu.add(Menu.NONE, R.id.action_room_control, Menu.NONE, "Room").also {
            it.setIcon(R.drawable.ic_queue_music)
            it.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        Log.d(LOG_TAG, "onCreateOptionsMenu: menu items added")

        // Observe room role to update room button visibility/title
        lifecycleScope.launch {
            syncController.roomState.collectLatest { state ->
                val item = roomMenuItem ?: return@collectLatest
                when (state.role) {
                    RoomRole.SOLO -> {
                        item.isVisible = false
                    }
                    RoomRole.HOSTING -> {
                        item.isVisible = true
                        item.title = "Hosting"
                    }
                    RoomRole.FOLLOWING -> {
                        item.isVisible = true
                        item.title = "Following"
                    }
                }
            }
        }
        return result
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_room_control) {
            Log.i(LOG_TAG, "opening RoomControlSheet")
            RoomControlSheet.newInstance().show(supportFragmentManager, "RoomControlSheet")
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupNavigationController() {
        val navController = findNavController(R.id.fragment_container)
        val navInflater = navController.navInflater
        val navGraph = navInflater.inflate(R.navigation.main_graph)

        navGraph.setStartDestination(R.id.action_familyhub_home)
        navController.graph = navGraph
        navigationView.setupWithNavController(navController)
        // Scroll Fragment to top
        navigationView.setOnItemReselectedListener {
            currentFragment(R.id.fragment_container).apply {
                if (this is IScrollHelper) {
                    scrollToTop()
                }
            }
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == navGraph.startDestinationId) {
                currentFragment(R.id.fragment_container)?.enterTransition = null
            }
            when (destination.id) {
                R.id.action_familyhub_home, R.id.action_live, R.id.action_cinema, R.id.action_familyhub_music, R.id.action_home, R.id.action_more, R.id.action_song, R.id.action_album, R.id.action_artist, R.id.action_folder, R.id.action_playlist, R.id.action_genre, R.id.action_search -> {
                    // Save the last tab
                    if (PreferenceUtil.rememberLastTab) {
                        saveTab(destination.id)
                    }
                    // Show Bottom Navigation Bar
                    setBottomNavVisibility(visible = true, animate = true)
                }
                R.id.playing_queue_fragment -> {
                    setBottomNavVisibility(visible = false, hideBottomSheet = true)
                }
                else -> setBottomNavVisibility(
                    visible = false,
                    animate = true
                ) // Hide Bottom Navigation Bar
            }
        }
    }

    private fun saveTab(id: Int) {
        if (PreferenceUtil.libraryCategory.firstOrNull { it.category.id == id }?.visible == true) {
            PreferenceUtil.lastTab = id
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val expand = intent?.extra<Boolean>(EXPAND_PANEL)?.value ?: false
        if (expand && PreferenceUtil.isExpandPanel) {
            fromNotification = true
            slidingPanel.bringToFront()
            expandPanel()
            intent?.removeExtra(EXPAND_PANEL)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        intent ?: return
        handlePlaybackIntent(intent)
    }

    @Suppress("deprecation")
    private fun handlePlaybackIntent(intent: Intent) {
        lifecycleScope.launch(IO) {
            val uri: Uri? = intent.data
            val mimeType: String? = intent.type
            var handled = false
            if (intent.action != null &&
                intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
            ) {
                val songs: List<Song> = getSongs(intent.extras!!)
                if (MusicPlayerRemote.shuffleMode == MusicService.SHUFFLE_MODE_SHUFFLE) {
                    MusicPlayerRemote.openAndShuffleQueue(songs, true)
                } else {
                    MusicPlayerRemote.openQueue(songs, 0, true)
                }
                handled = true
            }
            if (uri != null && uri.toString().isNotEmpty()) {
                MusicPlayerRemote.playFromUri(this@MainActivity, uri)
                handled = true
            } else if (MediaStore.Audio.Playlists.CONTENT_TYPE == mimeType) {
                val id = parseLongFromIntent(intent, "playlistId", "playlist")
                if (id >= 0L) {
                    val position: Int = intent.getIntExtra("position", 0)
                    val songs: List<Song> = PlaylistSongsLoader.getPlaylistSongList(get(), id)
                    MusicPlayerRemote.openQueue(songs, position, true)
                    handled = true
                }
            } else if (MediaStore.Audio.Albums.CONTENT_TYPE == mimeType) {
                val id = parseLongFromIntent(intent, "albumId", "album")
                if (id >= 0L) {
                    val position: Int = intent.getIntExtra("position", 0)
                    val songs = libraryViewModel.albumById(id).songs
                    MusicPlayerRemote.openQueue(
                        songs,
                        position,
                        true
                    )
                    handled = true
                }
            } else if (MediaStore.Audio.Artists.CONTENT_TYPE == mimeType) {
                val id = parseLongFromIntent(intent, "artistId", "artist")
                if (id >= 0L) {
                    val position: Int = intent.getIntExtra("position", 0)
                    val songs: List<Song> = libraryViewModel.artistById(id).songs
                    MusicPlayerRemote.openQueue(
                        songs,
                        position,
                        true
                    )
                    handled = true
                }
            }
            if (handled) {
                setIntent(Intent())
            }
        }
    }

    private fun parseLongFromIntent(
        intent: Intent,
        longKey: String,
        stringKey: String,
    ): Long {
        var id = intent.getLongExtra(longKey, -1)
        if (id < 0) {
            val idString = intent.getStringExtra(stringKey)
            if (idString != null) {
                try {
                    id = idString.toLong()
                } catch (e: NumberFormatException) {
                    logE(e)
                }
            }
        }
        return id
    }
}
