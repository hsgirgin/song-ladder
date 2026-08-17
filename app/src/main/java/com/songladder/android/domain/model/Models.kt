package com.songladder.android.domain.model

import kotlinx.serialization.Serializable

const val BASE_RATING = 1200
const val K_FACTOR = 32
const val BASE_ELO = 1200.0

fun seedEloForScore(scoreTenths: Int?): Double =
    BASE_ELO + 80.0 * ((scoreTenths ?: 55) / 10.0 - 5.5)

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
    val rankingSubjectId: String = id,
    val externalId: String? = null,
    val sourceType: MusicSourceType = MusicSourceType.MANUAL,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val createdAt: Long,
    val scoreTenths: Int? = null,
    val rating: Int = BASE_RATING,
    val wins: Int = 0,
    val losses: Int = 0,
    val skips: Int = 0,
    val lastRankedAt: Long? = null
)

enum class ResponsivenessEpoch {
    NEW,
    EDITED
}

enum class MatchupOutcome {
    WIN,
    SKIP,
    UNKNOWN
}

enum class RankingPresentation {
    GRID,
    LIST
}

data class Tombstone(
    val sourceType: MusicSourceType,
    val externalId: String?,
    val normalizedTitle: String,
    val normalizedArtist: String,
    val scoreTenths: Int?,
    val seedElo: Double,
    val deletedAt: Long
)

data class RankingSubject(
    val id: String,
    val scoreTenths: Int? = null,
    val elo: Double = BASE_ELO,
    val wins: Int = 0,
    val losses: Int = 0,
    val skips: Int = 0,
    val lastRatedAt: Long? = null,
    val responsivenessEpoch: ResponsivenessEpoch = ResponsivenessEpoch.NEW,
    val completedMatchupsInEpoch: Int = 0,
    val sourceType: MusicSourceType = MusicSourceType.MANUAL,
    val externalId: String? = null,
    val normalizedTitle: String = "",
    val normalizedArtist: String = "",
    val tombstone: Tombstone? = null
)

data class MatchupEvent(
    val sequenceId: Long,
    val occurredAt: Long,
    val firstSubjectId: String,
    val secondSubjectId: String,
    val outcome: MatchupOutcome,
    val winnerSubjectId: String? = null,
    val loserSubjectId: String? = null,
    val winnerEffectiveK: Double? = null,
    val loserEffectiveK: Double? = null
)

data class EloMatchupResult(
    val winner: RankingSubject,
    val loser: RankingSubject,
    val winnerEffectiveK: Double,
    val loserEffectiveK: Double,
    val ratedAt: Long
)

data class RankingSettings(
    val autoPlayMatchupPreviews: Boolean = true,
    val showTips: Boolean = true,
    val presentation: RankingPresentation = RankingPresentation.GRID
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
    val rankingSubjectId: String = id,
    val externalId: String? = null,
    val sourceType: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val createdAt: Long
)

@Serializable
data class TombstoneExport(
    val sourceType: String = MusicSourceType.IMPORT.name,
    val externalId: String? = null,
    val normalizedTitle: String = "",
    val normalizedArtist: String = "",
    val scoreTenths: Int? = null,
    val seedElo: Double = BASE_ELO,
    val deletedAt: Long = 0L
)

@Serializable
data class RankingSubjectExport(
    val id: String,
    val scoreTenths: Int? = null,
    val elo: Double = BASE_ELO,
    val wins: Int = 0,
    val losses: Int = 0,
    val skips: Int = 0,
    val lastRatedAt: Long? = null,
    val responsivenessEpoch: String = ResponsivenessEpoch.NEW.name,
    val completedMatchupsInEpoch: Int = 0,
    val sourceType: String = MusicSourceType.MANUAL.name,
    val externalId: String? = null,
    val normalizedTitle: String = "",
    val normalizedArtist: String = "",
    val tombstone: TombstoneExport? = null
)

@Serializable
data class MatchupEventExport(
    val sequenceId: Long,
    val occurredAt: Long,
    val firstSubjectId: String,
    val secondSubjectId: String,
    val outcome: String,
    val winnerSubjectId: String? = null,
    val loserSubjectId: String? = null,
    val winnerEffectiveK: Double? = null,
    val loserEffectiveK: Double? = null
)

@Serializable
data class RankingSettingsExport(
    val autoPlayMatchupPreviews: Boolean = true,
    val showTips: Boolean = true,
    val presentation: String = RankingPresentation.GRID.name
)

@Serializable
data class AppStatsExport(
    val matchCount: Int = 0,
    val skipCount: Int = 0
)

@Serializable
data class ExportPayload(
    val schemaVersion: Int = 1,
    val songs: List<SongExport> = emptyList(),
    val rankingSubjects: List<RankingSubjectExport> = emptyList(),
    val matchupEvents: List<MatchupEventExport> = emptyList(),
    val rankingSettings: RankingSettingsExport = RankingSettingsExport(),
    val appStats: AppStatsExport = AppStatsExport()
)

data class ScoreSaveResult(
    val songId: String,
    val scoreTenths: Int,
    val visibleOrderChanged: Boolean = false
)

data class RankingHistoryDeletionResult(
    val rankingSubjectId: String?,
    val deletedEventCount: Int
)
