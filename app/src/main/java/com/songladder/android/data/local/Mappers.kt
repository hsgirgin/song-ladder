package com.songladder.android.data.local

import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.AppStatsExport
import com.songladder.android.domain.model.ExportPayload
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.MatchupEventExport
import com.songladder.android.domain.model.MatchupOutcome
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.RankingPresentation
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.RankingSettingsExport
import com.songladder.android.domain.model.RankingSubject
import com.songladder.android.domain.model.RankingSubjectExport
import com.songladder.android.domain.model.ResponsivenessEpoch
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongExport
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.model.Tombstone
import com.songladder.android.domain.model.TombstoneExport
import java.util.UUID
import kotlin.math.roundToInt

data class ExportEntities(
    val songs: List<SongEntity>,
    val subjects: List<RankingSubjectEntity>,
    val events: List<MatchupEventEntity>,
    val settings: RankingSettingsEntity,
    val appStats: AppStatsEntity
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

fun RankingSubject.toExport(): RankingSubjectExport = RankingSubjectExport(
    id = id,
    scoreTenths = scoreTenths,
    elo = elo,
    wins = wins,
    losses = losses,
    skips = skips,
    lastRatedAt = lastRatedAt,
    responsivenessEpoch = responsivenessEpoch.name,
    completedMatchupsInEpoch = completedMatchupsInEpoch,
    responsivenessEpochSequence = responsivenessEpochSequence,
    sourceType = sourceType.name,
    externalId = externalId,
    normalizedTitle = normalizedTitle,
    normalizedArtist = normalizedArtist,
    tombstone = tombstone?.toExport()
)

fun Tombstone.toExport(): TombstoneExport = TombstoneExport(
    sourceType = sourceType.name,
    externalId = externalId,
    normalizedTitle = normalizedTitle,
    normalizedArtist = normalizedArtist,
    scoreTenths = scoreTenths,
    seedElo = seedElo,
    deletedAt = deletedAt,
    suppressedExternalId = suppressedExternalId,
    suppressedSourceType = suppressedSourceType?.name,
    suppressedNormalizedTitle = suppressedNormalizedTitle,
    suppressedNormalizedArtist = suppressedNormalizedArtist
)

fun RankingSubjectEntity.toDomain(): RankingSubject {
    val tombstone = tombstoneDeletedAt?.let { deletedAt ->
        Tombstone(
            sourceType = (tombstoneSourceType ?: sourceType).toMusicSourceType(),
            externalId = tombstoneExternalId ?: externalId,
            normalizedTitle = normalizedTitle,
            normalizedArtist = normalizedArtist,
            scoreTenths = tombstoneScoreTenths ?: scoreTenths,
            seedElo = tombstoneSeedElo ?: elo,
            deletedAt = deletedAt,
            suppressedExternalId = tombstoneSuppressedExternalId,
            suppressedSourceType = tombstoneSuppressedSourceType?.toMusicSourceType(),
            suppressedNormalizedTitle = tombstoneSuppressedNormalizedTitle,
            suppressedNormalizedArtist = tombstoneSuppressedNormalizedArtist
        )
    }
    return RankingSubject(
        id = id,
        scoreTenths = scoreTenths,
        elo = elo,
        wins = wins,
        losses = losses,
        skips = skips,
        lastRatedAt = lastRatedAt,
        responsivenessEpoch = responsivenessEpoch.toResponsivenessEpoch(),
        completedMatchupsInEpoch = completedMatchupsInEpoch,
        responsivenessEpochSequence = responsivenessEpochSequence,
        sourceType = sourceType.toMusicSourceType(),
        externalId = externalId,
        normalizedTitle = normalizedTitle.ifBlank { tombstone?.normalizedTitle.orEmpty() },
        normalizedArtist = normalizedArtist.ifBlank { tombstone?.normalizedArtist.orEmpty() },
        tombstone = tombstone
    )
}

fun RankingSubject.toEntity(): RankingSubjectEntity = RankingSubjectEntity(
    id = id,
    scoreTenths = scoreTenths,
    elo = elo,
    wins = wins,
    losses = losses,
    skips = skips,
    lastRatedAt = lastRatedAt,
    responsivenessEpoch = responsivenessEpoch.name,
    completedMatchupsInEpoch = completedMatchupsInEpoch,
    responsivenessEpochSequence = responsivenessEpochSequence,
    sourceType = sourceType.name,
    externalId = externalId,
    normalizedTitle = normalizedTitle,
    normalizedArtist = normalizedArtist,
    tombstoneDeletedAt = tombstone?.deletedAt,
    tombstoneSourceType = tombstone?.sourceType?.name,
    tombstoneExternalId = tombstone?.externalId,
    tombstoneScoreTenths = tombstone?.scoreTenths,
    tombstoneSeedElo = tombstone?.seedElo,
    tombstoneSuppressedExternalId = tombstone?.suppressedExternalId,
    tombstoneSuppressedSourceType = tombstone?.suppressedSourceType?.name,
    tombstoneSuppressedNormalizedTitle = tombstone?.suppressedNormalizedTitle,
    tombstoneSuppressedNormalizedArtist = tombstone?.suppressedNormalizedArtist
)

fun RankingSubjectExport.toEntity(): RankingSubjectEntity {
    val tombstone = tombstone
    return RankingSubjectEntity(
        id = id,
        scoreTenths = scoreTenths,
        elo = elo,
        wins = wins,
        losses = losses,
        skips = skips,
        lastRatedAt = lastRatedAt,
        responsivenessEpoch = responsivenessEpoch.toResponsivenessEpoch().name,
        completedMatchupsInEpoch = completedMatchupsInEpoch,
        responsivenessEpochSequence = responsivenessEpochSequence,
        sourceType = sourceType.toMusicSourceType().name,
        externalId = externalId,
        normalizedTitle = normalizedTitle.ifBlank { tombstone?.normalizedTitle.orEmpty() },
        normalizedArtist = normalizedArtist.ifBlank { tombstone?.normalizedArtist.orEmpty() },
        tombstoneDeletedAt = tombstone?.deletedAt,
        tombstoneSourceType = tombstone?.sourceType?.toMusicSourceType()?.name,
        tombstoneExternalId = tombstone?.externalId,
        tombstoneScoreTenths = tombstone?.scoreTenths,
        tombstoneSeedElo = tombstone?.seedElo,
        tombstoneSuppressedExternalId = tombstone?.suppressedExternalId,
        tombstoneSuppressedSourceType = tombstone?.suppressedSourceType?.toMusicSourceType()?.name,
        tombstoneSuppressedNormalizedTitle = tombstone?.suppressedNormalizedTitle,
        tombstoneSuppressedNormalizedArtist = tombstone?.suppressedNormalizedArtist
    )
}

fun MatchupEvent.toEntity(): MatchupEventEntity = MatchupEventEntity(
    sequenceId = sequenceId,
    occurredAt = occurredAt,
    firstSubjectId = firstSubjectId,
    secondSubjectId = secondSubjectId,
    outcome = outcome.name,
    winnerSubjectId = winnerSubjectId,
    loserSubjectId = loserSubjectId,
    winnerEffectiveK = winnerEffectiveK,
    loserEffectiveK = loserEffectiveK
)

fun MatchupEventExport.toEntity(): MatchupEventEntity = MatchupEventEntity(
    sequenceId = sequenceId,
    occurredAt = occurredAt,
    firstSubjectId = firstSubjectId,
    secondSubjectId = secondSubjectId,
    outcome = outcome,
    winnerSubjectId = winnerSubjectId,
    loserSubjectId = loserSubjectId,
    winnerEffectiveK = winnerEffectiveK,
    loserEffectiveK = loserEffectiveK
)

fun MatchupEventEntity.toDomain(): MatchupEvent = MatchupEvent(
    sequenceId = sequenceId,
    occurredAt = occurredAt,
    firstSubjectId = firstSubjectId,
    secondSubjectId = secondSubjectId,
    outcome = outcome.toMatchupOutcome(),
    winnerSubjectId = winnerSubjectId,
    loserSubjectId = loserSubjectId,
    winnerEffectiveK = winnerEffectiveK,
    loserEffectiveK = loserEffectiveK
)

fun RankingSettingsEntity.toDomain(): RankingSettings = RankingSettings(
    autoPlayMatchupPreviews = autoPlayMatchupPreviews,
    showTips = showTips,
    presentation = presentation.toRankingPresentation()
)

fun RankingSettings.toEntity(): RankingSettingsEntity = RankingSettingsEntity(
    autoPlayMatchupPreviews = autoPlayMatchupPreviews,
    showTips = showTips,
    presentation = presentation.name
)

fun AppStatsEntity?.toDomain(): AppStats = AppStats(
    matchCount = this?.matchCount ?: 0,
    skipCount = this?.skipCount ?: 0
)

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
            presentation = rankingSettings.presentation.toRankingPresentation().name
        ),
        appStats = AppStatsEntity(
            matchCount = appStats.matchCount,
            skipCount = appStats.skipCount
        )
    )
}

fun ExportPayload.validateForImport() {
    require(schemaVersion == 1) { "Unsupported backup schema version: $schemaVersion" }

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
            presentation = settings.presentation
        ),
        appStats = AppStatsExport(
            matchCount = appStats.matchCount,
            skipCount = appStats.skipCount
        )
    )
}

internal fun String.toMusicSourceType(): MusicSourceType =
    runCatching { MusicSourceType.valueOf(trim().uppercase()) }
        .getOrDefault(MusicSourceType.IMPORT)

private fun String.toResponsivenessEpoch(): ResponsivenessEpoch =
    runCatching { ResponsivenessEpoch.valueOf(trim().uppercase()) }
        .getOrDefault(ResponsivenessEpoch.NEW)

private fun String.toMatchupOutcome(): MatchupOutcome =
    runCatching { MatchupOutcome.valueOf(trim().uppercase()) }
        .getOrDefault(MatchupOutcome.UNKNOWN)

private fun String.toRankingPresentation(): RankingPresentation =
    runCatching { RankingPresentation.valueOf(trim().uppercase()) }
        .getOrDefault(RankingPresentation.GRID)
