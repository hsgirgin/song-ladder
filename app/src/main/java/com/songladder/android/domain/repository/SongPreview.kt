package com.songladder.android.domain.repository

import com.songladder.android.domain.model.Song
import kotlinx.coroutines.flow.Flow

interface SongPreviewResolver {
    suspend fun resolve(song: Song): String?

    /** Drops any cached result for [song] so the next [resolve] call re-checks the network. */
    fun invalidate(song: Song) {}
}

interface SongPreviewPlayer {
    val events: Flow<SongPreviewPlaybackEvent>
    fun play(songId: String, url: String)
    fun pause()
    fun stop()
}

data class SongPreviewPlaybackEvent(
    val songId: String,
    val failed: Boolean = false
)
