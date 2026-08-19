package com.songladder.android.ui

import com.songladder.android.domain.model.Song
import com.songladder.android.domain.repository.SongPreviewPlaybackEvent
import com.songladder.android.domain.repository.SongPreviewPlayer
import com.songladder.android.domain.repository.SongPreviewResolver
import kotlinx.coroutines.flow.emptyFlow

internal data object UnavailablePreviewResolver : SongPreviewResolver {
    override suspend fun resolve(song: Song): String? = null
}

internal data object NoOpPreviewPlayer : SongPreviewPlayer {
    override val events = emptyFlow<SongPreviewPlaybackEvent>()

    override fun play(songId: String, url: String) = Unit

    override fun pause() = Unit

    override fun stop() = Unit
}
