package com.songladder.android.data.local

import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongExport
import com.songladder.android.domain.model.SongInput
import java.util.UUID
import kotlin.math.roundToInt

fun SongWithStatsEntity.toDomain(): Song {
    return Song(
        id = song.id,
        rankingSubjectId = song.rankingSubjectId,
        externalId = song.externalId,
        sourceType = song.sourceType.toMusicSourceType(),
        title = song.title,
        artist = song.artist,
        album = song.album,
        artworkUrl = song.artworkUrl,
        createdAt = song.createdAt,
        scoreTenths = stats.scoreTenths,
        elo = stats.elo,
        rating = stats.elo.roundToInt(),
        wins = stats.wins,
        losses = stats.losses,
        skips = stats.skips,
        lastRankedAt = stats.lastRatedAt
    )
}

fun SongInput.toSongAndRankingSubjectEntities(id: String = UUID.randomUUID().toString()): Pair<SongEntity, RankingSubjectEntity> {
    val normalizedTitle = title.trim().lowercase()
    val normalizedArtist = artist.trim().lowercase()
    return SongEntity(
        id = id,
        rankingSubjectId = id,
        externalId = externalId,
        sourceType = sourceType.name,
        title = title.trim(),
        artist = artist.trim(),
        album = album.trim(),
        artworkUrl = artworkUrl,
        createdAt = System.currentTimeMillis()
    ) to RankingSubjectEntity(
        id = id,
        sourceType = sourceType.name,
        externalId = externalId,
        normalizedTitle = normalizedTitle,
        normalizedArtist = normalizedArtist
    )
}

fun Song.toSongExport(): SongExport = SongExport(
    id = id,
    rankingSubjectId = rankingSubjectId,
    externalId = externalId,
    sourceType = sourceType.name,
    title = title,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
    createdAt = createdAt
)
