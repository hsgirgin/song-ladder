package com.songladder.android.domain.model

import kotlin.math.ceil
import kotlin.math.roundToInt

enum class AlbumMatchStatus {
    PENDING,
    AUTO_MATCHED,
    NEEDS_REVIEW,
    CONFIRMED,
    NO_MATCH
}

enum class AlbumMetadataProviderType {
    ITUNES
}

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val normalizedTitle: String = "",
    val normalizedArtist: String = "",
    val providerSourceType: AlbumMetadataProviderType = AlbumMetadataProviderType.ITUNES,
    val providerCollectionId: String? = null,
    val providerTrackCount: Int? = null,
    val matchStatus: AlbumMatchStatus = AlbumMatchStatus.PENDING,
    val matchConfidence: Double? = null,
    val createdAt: Long = 0L,
    val lastMatchAttemptAt: Long? = null,
    val lastMatchedAt: Long? = null
)

data class AlbumTrackExclusion(
    val songId: String,
    val albumId: String,
    val excludedAt: Long
)

data class AlbumMissingTrack(
    val albumId: String,
    val providerTrackId: String,
    val title: String,
    val trackNumber: Int? = null,
    val artworkUrl: String? = null
)

data class AlbumTrackRow(
    val song: Song,
    val excludedFromAverage: Boolean,
    val trackNumber: Int? = null
)

data class RankedAlbum(
    val rank: Int,
    val album: Album,
    val scoreTenths: Int?,
    val includedRatedTrackCount: Int,
    val totalOwnedTrackCount: Int
)

data class AlbumDetail(
    val album: Album,
    val tracks: List<AlbumTrackRow>,
    val missingTracks: List<AlbumMissingTrack>,
    val scoreTenths: Int?,
    val includedRatedTrackCount: Int
)

// Deterministic so re-discovering the same (artist, album) grouping across app runs
// always maps back to the same AlbumEntity row, with no separate sync step needed.
fun normalizedAlbumId(normalizedArtist: String, normalizedAlbumTitle: String): String =
    "$normalizedArtist::$normalizedAlbumTitle"

// Threshold scales with release size (ceil(providerTrackCount / 2)) rather than a flat
// count, so a two-track EP isn't held to the same bar as a twelve-track album. Returns
// null (unranked) until the release is matched (providerTrackCount known) and enough of
// its included tracks are rated.
fun computeAlbumScoreTenths(includedRatedScoreTenths: List<Int>, providerTrackCount: Int?): Int? {
    if (providerTrackCount == null || providerTrackCount <= 0) return null
    if (includedRatedScoreTenths.isEmpty()) return null
    val threshold = ceil(providerTrackCount / 2.0).toInt()
    if (includedRatedScoreTenths.size < threshold) return null
    return (includedRatedScoreTenths.sum().toDouble() / includedRatedScoreTenths.size).roundToInt()
}
