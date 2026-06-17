package com.songladder.android.domain.model

import kotlinx.serialization.Serializable

const val BASE_RATING = 1200
const val K_FACTOR = 32

enum class MusicSourceType {
    MANUAL,
    SAMPLE,
    ITUNES,
    YOUTUBE_MUSIC,
    SPOTIFY,
    IMPORT
}

data class Song(
    val id: String,
    val externalId: String? = null,
    val sourceType: MusicSourceType = MusicSourceType.MANUAL,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val createdAt: Long,
    val rating: Int = BASE_RATING,
    val wins: Int = 0,
    val losses: Int = 0,
    val skips: Int = 0,
    val lastRankedAt: Long? = null
)

data class Matchup(
    val left: Song,
    val right: Song
)

data class AppStats(
    val matchCount: Int = 0,
    val skipCount: Int = 0
)

data class SongInput(
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val sourceType: MusicSourceType = MusicSourceType.MANUAL,
    val externalId: String? = null
)

data class MusicTrackCandidate(
    val externalId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val sourceType: MusicSourceType = MusicSourceType.ITUNES
)

data class PlaylistImportPreview(
    val playlistTitle: String,
    val importableTracks: List<MusicTrackCandidate>,
    val ambiguousTracks: List<AmbiguousPlaylistTrack>,
    val unsupportedCount: Int = 0
)

data class AmbiguousPlaylistTrack(
    val rawTitle: String = "",
    val rawArtist: String = "",
    val reason: String
)

@Serializable
data class SongExport(
    val id: String,
    val externalId: String? = null,
    val sourceType: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val createdAt: Long,
    val rating: Int = BASE_RATING,
    val wins: Int = 0,
    val losses: Int = 0,
    val skips: Int = 0,
    val lastRankedAt: Long? = null
)

@Serializable
data class ExportPayload(
    val songs: List<SongExport>,
    val matchCount: Int = 0,
    val skipCount: Int = 0
)
