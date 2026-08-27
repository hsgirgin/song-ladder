package com.songladder.android.data.local

import com.songladder.android.domain.model.Album
import com.songladder.android.domain.model.AlbumExport
import com.songladder.android.domain.model.AlbumMatchStatus
import com.songladder.android.domain.model.AlbumMetadataProviderType
import com.songladder.android.domain.model.AlbumMissingTrack
import com.songladder.android.domain.model.AlbumTrackExclusion
import com.songladder.android.domain.model.AlbumTrackExclusionExport

fun AlbumEntity.toDomain(): Album = Album(
    id = id,
    title = title,
    artist = artist,
    artworkUrl = artworkUrl,
    normalizedTitle = normalizedTitle,
    normalizedArtist = normalizedArtist,
    providerSourceType = providerSourceType.toAlbumMetadataProviderType(),
    providerCollectionId = providerCollectionId,
    providerTrackCount = providerTrackCount,
    matchStatus = matchStatus.toAlbumMatchStatus(),
    matchConfidence = matchConfidence,
    createdAt = createdAt,
    lastMatchAttemptAt = lastMatchAttemptAt,
    lastMatchedAt = lastMatchedAt
)

fun Album.toEntity(): AlbumEntity = AlbumEntity(
    id = id,
    title = title,
    artist = artist,
    artworkUrl = artworkUrl,
    normalizedTitle = normalizedTitle,
    normalizedArtist = normalizedArtist,
    providerSourceType = providerSourceType.name,
    providerCollectionId = providerCollectionId,
    providerTrackCount = providerTrackCount,
    matchStatus = matchStatus.name,
    matchConfidence = matchConfidence,
    createdAt = createdAt,
    lastMatchAttemptAt = lastMatchAttemptAt,
    lastMatchedAt = lastMatchedAt
)

// Auto-match state (AUTO_MATCHED/NEEDS_REVIEW/NO_MATCH, confidence, provider track
// count) is matcher-derived and deliberately absent from AlbumExport - only a user's
// explicit confirmation survives a backup, so a confirmed pick becomes CONFIRMED again
// and everything else downgrades to PENDING for the matcher to re-resolve.
fun AlbumExport.toEntity(): AlbumEntity = AlbumEntity(
    id = id,
    title = title,
    artist = artist,
    artworkUrl = artworkUrl,
    normalizedTitle = normalizedTitle,
    normalizedArtist = normalizedArtist,
    providerSourceType = confirmedProviderSourceType ?: AlbumMetadataProviderType.ITUNES.name,
    providerCollectionId = confirmedProviderCollectionId,
    providerTrackCount = null,
    matchStatus = if (confirmedProviderCollectionId != null) {
        AlbumMatchStatus.CONFIRMED.name
    } else {
        AlbumMatchStatus.PENDING.name
    },
    matchConfidence = null,
    createdAt = createdAt,
    lastMatchAttemptAt = null,
    lastMatchedAt = null
)

fun AlbumEntity.toExport(): AlbumExport {
    val isConfirmed = matchStatus.toAlbumMatchStatus() == AlbumMatchStatus.CONFIRMED
    return AlbumExport(
        id = id,
        title = title,
        artist = artist,
        artworkUrl = artworkUrl,
        normalizedTitle = normalizedTitle,
        normalizedArtist = normalizedArtist,
        createdAt = createdAt,
        confirmedProviderSourceType = if (isConfirmed) providerSourceType else null,
        confirmedProviderCollectionId = if (isConfirmed) providerCollectionId else null
    )
}

fun AlbumTrackExclusionEntity.toDomain(): AlbumTrackExclusion = AlbumTrackExclusion(
    songId = songId,
    albumId = albumId,
    excludedAt = excludedAt
)

fun AlbumTrackExclusion.toEntity(): AlbumTrackExclusionEntity = AlbumTrackExclusionEntity(
    songId = songId,
    albumId = albumId,
    excludedAt = excludedAt
)

fun AlbumTrackExclusionExport.toEntity(): AlbumTrackExclusionEntity = AlbumTrackExclusionEntity(
    songId = songId,
    albumId = albumId,
    excludedAt = excludedAt
)

fun AlbumTrackExclusionEntity.toExport(): AlbumTrackExclusionExport = AlbumTrackExclusionExport(
    songId = songId,
    albumId = albumId,
    excludedAt = excludedAt
)

fun AlbumMissingTrackEntity.toDomain(): AlbumMissingTrack = AlbumMissingTrack(
    albumId = albumId,
    providerTrackId = providerTrackId,
    title = title,
    trackNumber = trackNumber,
    artworkUrl = artworkUrl
)

fun AlbumMissingTrack.toEntity(): AlbumMissingTrackEntity = AlbumMissingTrackEntity(
    albumId = albumId,
    providerTrackId = providerTrackId,
    title = title,
    trackNumber = trackNumber,
    artworkUrl = artworkUrl
)
