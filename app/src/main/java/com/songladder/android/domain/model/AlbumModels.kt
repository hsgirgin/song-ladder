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

data class AlbumTrackRow(
    val song: Song,
    val excludedFromAverage: Boolean,
    val trackNumber: Int? = null
)

// rank is nullable because the repository layer (which emits these) doesn't know the
// final display order - that depends on UI-level filtering (e.g. singles are hidden)
// and sorting, so it's assigned once by whoever builds the final displayed list.
data class RankedAlbum(
    val rank: Int?,
    val album: Album,
    val scoreTenths: Int?,
    val includedRatedTrackCount: Int,
    val totalOwnedTrackCount: Int
)

data class AlbumDetail(
    val album: Album,
    val tracks: List<AlbumTrackRow>,
    // Release tracks with no owned-song title match anywhere in the artist's library -
    // see AlbumMatchingEngine.missingTracks. Reuses AlbumReleaseTrack (the same shape a
    // provider lookup returns) rather than a dedicated type, since this is just that
    // list filtered down, not independently persisted.
    val missingTracks: List<AlbumReleaseTrack>,
    val scoreTenths: Int?,
    val includedRatedTrackCount: Int
)

/** A release returned by a metadata provider's search, not yet fully looked up. */
data class AlbumReleaseCandidate(
    val collectionId: String,
    val collectionName: String,
    val artistName: String,
    val artworkUrl: String? = null,
    val trackCount: Int? = null
)

data class AlbumReleaseTrack(
    val trackId: String,
    val title: String,
    val trackNumber: Int? = null,
    val artworkUrl: String? = null
)

/** The full result of looking up one release by its provider collection id. */
data class AlbumReleaseLookup(
    val collectionId: String,
    val collectionName: String,
    val artistName: String,
    val artworkUrl: String? = null,
    val trackCount: Int? = null,
    val tracks: List<AlbumReleaseTrack> = emptyList()
)

data class AlbumMatchOutcome(
    val status: AlbumMatchStatus,
    val bestCandidate: AlbumReleaseCandidate? = null,
    val confidence: Double? = null
)

// Deterministic so re-discovering the same (artist, album) grouping across app runs
// always maps back to the same AlbumEntity row, with no separate sync step needed.
fun normalizedAlbumId(normalizedArtist: String, normalizedAlbumTitle: String): String =
    "$normalizedArtist::$normalizedAlbumTitle"

// No elo/lastRankedAt tie-break to mirror: an album's score is a derived average, not
// an independently-earned rating, so ties fall back to a stable alphabetical order.
fun albumScoreFirstComparator(): Comparator<RankedAlbum> = compareByDescending<RankedAlbum> {
    it.scoreTenths ?: Int.MIN_VALUE
}.thenBy {
    it.album.title.trim().lowercase()
}.thenBy {
    it.album.id
}

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
