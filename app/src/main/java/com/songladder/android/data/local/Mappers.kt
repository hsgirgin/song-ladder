package com.songladder.android.data.local

import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.ExportPayload
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongExport
import com.songladder.android.domain.model.SongInput
import java.util.UUID

fun SongWithStatsEntity.toDomain(): Song {
    return Song(
        id = song.id,
        externalId = song.externalId,
        sourceType = song.sourceType.toMusicSourceType(),
        title = song.title,
        artist = song.artist,
        album = song.album,
        artworkUrl = song.artworkUrl,
        createdAt = song.createdAt,
        rating = stats.rating,
        wins = stats.wins,
        losses = stats.losses,
        skips = stats.skips,
        lastRankedAt = stats.lastRankedAt
    )
}

fun SongInput.toSongEntity(): SongEntity {
    return SongEntity(
        id = UUID.randomUUID().toString(),
        externalId = externalId,
        sourceType = sourceType.name,
        title = title.trim(),
        artist = artist.trim(),
        album = album.trim(),
        artworkUrl = artworkUrl,
        createdAt = System.currentTimeMillis()
    )
}

fun Song.toSongExport(): SongExport {
    return SongExport(
        id = id,
        externalId = externalId,
        sourceType = sourceType.name,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        createdAt = createdAt,
        rating = rating,
        wins = wins,
        losses = losses,
        skips = skips,
        lastRankedAt = lastRankedAt
    )
}

fun ExportPayload.toEntities(): Pair<List<SongEntity>, List<RankingStatsEntity>> {
    val exportedSongs = songs
    val songEntities = exportedSongs.map {
        SongEntity(
            id = it.id,
            externalId = it.externalId,
            sourceType = it.sourceType.toMusicSourceType().name,
            title = it.title.ifBlank { "Untitled track" },
            artist = it.artist.ifBlank { "Unknown artist" },
            album = it.album,
            artworkUrl = it.artworkUrl,
            createdAt = it.createdAt
        )
    }

    val stats = exportedSongs.map { export ->
        RankingStatsEntity(
            songId = export.id,
            rating = export.rating,
            wins = export.wins,
            losses = export.losses,
            skips = export.skips,
            lastRankedAt = export.lastRankedAt
        )
    }

    return songEntities to stats
}

fun AppStatsEntity?.toDomain(): AppStats = AppStats(
    matchCount = this?.matchCount ?: 0,
    skipCount = this?.skipCount ?: 0
)

private fun String.toMusicSourceType(): MusicSourceType =
    runCatching { MusicSourceType.valueOf(trim().uppercase()) }
        .getOrDefault(MusicSourceType.IMPORT)
