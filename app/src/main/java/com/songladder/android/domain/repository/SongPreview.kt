package com.songladder.android.domain.repository

import com.songladder.android.domain.model.Song
import kotlinx.coroutines.flow.Flow

interface SongPreviewResolver {
    suspend fun resolve(song: Song): String?
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
