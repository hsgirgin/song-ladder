package com.songladder.android.data.local

import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.model.AppStatsExport
import com.songladder.android.domain.model.ExportPayload
import com.songladder.android.domain.model.MatchupEventExport
import com.songladder.android.domain.model.MatchupOutcome
import com.songladder.android.domain.model.RankingSettingsExport
import com.songladder.android.domain.model.RankingSubjectExport
import com.songladder.android.domain.model.SongExport

data class ExportEntities(
    val songs: List<SongEntity>,
    val subjects: List<RankingSubjectEntity>,
    val events: List<MatchupEventEntity>,
    val settings: RankingSettingsEntity,
    val appStats: AppStatsEntity,
    val albums: List<AlbumEntity> = emptyList(),
    val albumTrackExclusions: List<AlbumTrackExclusionEntity> = emptyList()
)

data class RecomputedExportEntities(
    val entities: ExportEntities,
    val repairedSubjectCount: Int
)

fun ExportEntities.recomputeDerivedState(
    matchupEngine: EloMatchupEngine
): ExportEntities = recomputeDerivedStateWithRepairCount(matchupEngine).entities

fun ExportEntities.recomputeDerivedStateWithRepairCount(
    matchupEngine: EloMatchupEngine
): RecomputedExportEntities {
    val domainEvents = events.map { it.toDomain() }
    val replayedSubjects = matchupEngine
        .replay(subjects.map { it.toDomain() }, domainEvents)
        .map { it.toEntity() }
    val originalSubjectsById = subjects.associateBy { it.id }
    val repairedSubjectCount = replayedSubjects.count { replayed ->
        originalSubjectsById[replayed.id]?.hasDifferentDerivedStateThan(replayed) ?: true
    }
    return RecomputedExportEntities(
        entities = copy(
            subjects = replayedSubjects,
            appStats = AppStatsEntity(
                matchCount = domainEvents.count { it.outcome == MatchupOutcome.WIN },
                skipCount = domainEvents.count { it.outcome == MatchupOutcome.SKIP }
            )
        ),
        repairedSubjectCount = repairedSubjectCount
    )
}

private fun RankingSubjectEntity.hasDifferentDerivedStateThan(other: RankingSubjectEntity): Boolean =
    elo != other.elo ||
        wins != other.wins ||
        losses != other.losses ||
        skips != other.skips ||
        completedMatchupsInEpoch != other.completedMatchupsInEpoch

fun ExportPayload.toEntities(): ExportEntities {
    val subjectExports = rankingSubjects.associateBy { it.id }
    val subjectsBySongId = songs.associate { song ->
        val subjectId = song.rankingSubjectId
        subjectId to (subjectExports[subjectId] ?: RankingSubjectExport(
            id = subjectId,
            sourceType = song.sourceType,
            externalId = song.externalId,
            normalizedTitle = song.title.trim().lowercase(),
            normalizedArtist = song.artist.trim().lowercase()
        ))
    }
    val songEntities = songs.map { song ->
        SongEntity(
            id = song.id,
            rankingSubjectId = song.rankingSubjectId,
            externalId = song.externalId,
            sourceType = song.sourceType.toMusicSourceType().name,
            title = song.title.ifBlank { "Untitled track" },
            artist = song.artist.ifBlank { "Unknown artist" },
            album = song.album,
            artworkUrl = song.artworkUrl,
            createdAt = song.createdAt
        )
    }
    val subjectEntities = rankingSubjects.map { it.toEntity() }.toMutableList()
    subjectsBySongId.values
        .filter { subject -> subjectEntities.none { it.id == subject.id } }
        .mapTo(subjectEntities) { it.toEntity() }

    return ExportEntities(
        songs = songEntities,
        subjects = subjectEntities,
        events = matchupEvents.map { it.toEntity() },
        settings = RankingSettingsEntity(
            autoPlayMatchupPreviews = rankingSettings.autoPlayMatchupPreviews,
            showTips = rankingSettings.showTips,
            presentation = rankingSettings.presentation.toRankingPresentation().name,
            metadataRetrievalEnabled = rankingSettings.metadataRetrievalEnabled
        ),
        appStats = AppStatsEntity(
            matchCount = appStats.matchCount,
            skipCount = appStats.skipCount
        ),
        albums = albums.map { it.toEntity() },
        albumTrackExclusions = albumTrackExclusions.map { it.toEntity() }
    )
}

fun ExportPayload.validateForImport() {
    require(schemaVersion in 1..2) { "Unsupported backup schema version: $schemaVersion" }

    val songIds = songs.map { song ->
        require(song.id.isNotBlank()) { "Song IDs must not be blank." }
        require(song.rankingSubjectId.isNotBlank()) { "Song ranking subject IDs must not be blank." }
        song.id
    }
    require(songIds.size == songIds.toSet().size) { "Backup contains duplicate song IDs." }

    val subjectIds = rankingSubjects.map { subject ->
        require(subject.id.isNotBlank()) { "Ranking subject IDs must not be blank." }
        require(subject.scoreTenths == null || subject.scoreTenths in 10..100) {
            "Ranking subject scores must be between 10 and 100 tenths."
        }
        require(subject.elo.isFinite()) { "Ranking subject Elo must be finite." }
        require(subject.wins >= 0 && subject.losses >= 0 && subject.skips >= 0) {
            "Ranking subject counters must not be negative."
        }
        require(subject.completedMatchupsInEpoch >= 0) {
            "Completed matchup counts must not be negative."
        }
        require(subject.responsivenessEpochSequence >= 0L) {
            "Responsiveness epoch sequence must not be negative."
        }
        subject.tombstone?.let { tombstone ->
            require(tombstone.deletedAt > 0L) { "Tombstone deletion times must be positive." }
            require(tombstone.scoreTenths == null || tombstone.scoreTenths in 10..100) {
                "Tombstone scores must be between 10 and 100 tenths."
            }
            require(tombstone.seedElo.isFinite()) { "Tombstone seed Elo must be finite." }
        }
        subject.id
    }
    require(subjectIds.size == subjectIds.toSet().size) {
        "Backup contains duplicate ranking subject IDs."
    }
    val knownSubjectIds = subjectIds.toSet()
    val subjectsById = rankingSubjects.associateBy { it.id }

    songs.forEach { song ->
        require(song.rankingSubjectId in knownSubjectIds) {
            "Song ${song.id} references a missing ranking subject."
        }
        require(subjectsById.getValue(song.rankingSubjectId).tombstone == null) {
            "Active songs must not reference tombstoned ranking subjects."
        }
    }
    val activeSongSubjectIds = songs.map { it.rankingSubjectId }
    require(activeSongSubjectIds.size == activeSongSubjectIds.toSet().size) {
        "Each active song must have a unique ranking subject."
    }

    val sequenceIds = matchupEvents.map { event ->
        require(event.sequenceId > 0L) { "Matchup event sequence IDs must be positive." }
        require(event.occurredAt >= 0L) { "Matchup event timestamps must not be negative." }
        require(event.firstSubjectId in knownSubjectIds && event.secondSubjectId in knownSubjectIds) {
            "Matchup events must reference existing ranking subjects."
        }
        require(event.firstSubjectId != event.secondSubjectId) {
            "Matchup events require two distinct ranking subjects."
        }
        event.winnerSubjectId?.let { winnerId ->
            require(winnerId in knownSubjectIds) { "Matchup winner references a missing subject." }
        }
        event.loserSubjectId?.let { loserId ->
            require(loserId in knownSubjectIds) { "Matchup loser references a missing subject." }
        }
        require(event.winnerSubjectId == null || event.loserSubjectId == null ||
            event.winnerSubjectId != event.loserSubjectId) {
            "Matchup winners and losers must be distinct."
        }
        require(event.winnerEffectiveK == null ||
            event.winnerEffectiveK.isFinite() && event.winnerEffectiveK >= 0.0) {
            "Winner effective K must be finite and non-negative."
        }
        require(event.loserEffectiveK == null ||
            event.loserEffectiveK.isFinite() && event.loserEffectiveK >= 0.0) {
            "Loser effective K must be finite and non-negative."
        }

        when (event.outcome.trim().uppercase()) {
            "WIN" -> {
                require(event.winnerSubjectId != null && event.loserSubjectId != null) {
                    "Winner events require winner and loser subjects."
                }
                require(event.winnerSubjectId in setOf(event.firstSubjectId, event.secondSubjectId)) {
                    "Winner events must reference a matchup participant."
                }
                require(event.loserSubjectId in setOf(event.firstSubjectId, event.secondSubjectId)) {
                    "Loser events must reference a matchup participant."
                }
                require(event.winnerEffectiveK != null && event.loserEffectiveK != null) {
                    "Winner events require both effective K values."
                }
            }

            "SKIP" -> {
                require(event.winnerSubjectId == null && event.loserSubjectId == null) {
                    "Skipped events must not have a winner or loser."
                }
                require(event.winnerEffectiveK == null && event.loserEffectiveK == null) {
                    "Skipped events must not have effective K values."
                }
            }
        }
        event.sequenceId
    }
    require(sequenceIds.size == sequenceIds.toSet().size) {
        "Backup contains duplicate matchup event sequence IDs."
    }

    val albumIds = albums.map { album ->
        require(album.id.isNotBlank()) { "Album IDs must not be blank." }
        album.id
    }
    require(albumIds.size == albumIds.toSet().size) { "Backup contains duplicate album IDs." }
    val knownAlbumIds = albumIds.toSet()
    val knownSongIds = songIds.toSet()

    val exclusionSongIds = albumTrackExclusions.map { exclusion ->
        require(exclusion.songId.isNotBlank()) { "Album track exclusion song IDs must not be blank." }
        require(exclusion.albumId.isNotBlank()) { "Album track exclusion album IDs must not be blank." }
        require(exclusion.songId in knownSongIds) {
            "Album track exclusion references a missing song."
        }
        require(exclusion.albumId in knownAlbumIds) {
            "Album track exclusion references a missing album."
        }
        exclusion.songId
    }
    require(exclusionSongIds.size == exclusionSongIds.toSet().size) {
        "Each song may only be excluded from one album."
    }

    require(appStats.matchCount >= 0 && appStats.skipCount >= 0) {
        "App counters must not be negative."
    }
}

fun ExportEntities.toPayload(): ExportPayload {
    val songsById = songs.associateBy { it.id }
    return ExportPayload(
        songs = songs.map { song ->
            val activeSong = songsById.getValue(song.id)
            SongExport(
                id = activeSong.id,
                rankingSubjectId = activeSong.rankingSubjectId,
                externalId = activeSong.externalId,
                sourceType = activeSong.sourceType,
                title = activeSong.title,
                artist = activeSong.artist,
                album = activeSong.album,
                artworkUrl = activeSong.artworkUrl,
                createdAt = activeSong.createdAt
            )
        },
        rankingSubjects = subjects.map { it.toDomain().toExport() },
        matchupEvents = events.map {
            MatchupEventExport(
                sequenceId = it.sequenceId,
                occurredAt = it.occurredAt,
                firstSubjectId = it.firstSubjectId,
                secondSubjectId = it.secondSubjectId,
                outcome = it.outcome,
                winnerSubjectId = it.winnerSubjectId,
                loserSubjectId = it.loserSubjectId,
                winnerEffectiveK = it.winnerEffectiveK,
                loserEffectiveK = it.loserEffectiveK
            )
        },
        rankingSettings = RankingSettingsExport(
            autoPlayMatchupPreviews = settings.autoPlayMatchupPreviews,
            showTips = settings.showTips,
            presentation = settings.presentation,
            metadataRetrievalEnabled = settings.metadataRetrievalEnabled
        ),
        appStats = AppStatsExport(
            matchCount = appStats.matchCount,
            skipCount = appStats.skipCount
        ),
        albums = albums.map { it.toExport() },
        albumTrackExclusions = albumTrackExclusions.map { it.toExport() }
    )
}
