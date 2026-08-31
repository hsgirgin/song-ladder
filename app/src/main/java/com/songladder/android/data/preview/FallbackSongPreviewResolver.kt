package com.songladder.android.data.preview

import com.songladder.android.domain.model.Song
import com.songladder.android.domain.repository.SongPreviewResolver

class FallbackSongPreviewResolver(
    private val resolvers: List<SongPreviewResolver>
) : SongPreviewResolver {
    override suspend fun resolve(song: Song): String? {
        for (resolver in resolvers) {
            resolver.resolve(song)?.let { return it }
        }
        return null
    }

    override fun invalidate(song: Song) {
        resolvers.forEach { it.invalidate(song) }
    }
}
