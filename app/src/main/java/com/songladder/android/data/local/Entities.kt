package com.songladder.android.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.songladder.android.domain.model.BASE_ELO

@Entity(
    tableName = "songs",
    foreignKeys = [
        ForeignKey(
            entity = RankingSubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["rankingSubjectId"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [Index(value = ["rankingSubjectId"], unique = true)]
)
data class SongEntity(
    @PrimaryKey val id: String,
    val rankingSubjectId: String,
    val externalId: String? = null,
    val sourceType: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val createdAt: Long
)

@Entity(tableName = "ranking_subjects")
data class RankingSubjectEntity(
    @PrimaryKey val id: String,
    val scoreTenths: Int? = null,
    val elo: Double = BASE_ELO,
    val wins: Int = 0,
    val losses: Int = 0,
    val skips: Int = 0,
    val lastRatedAt: Long? = null,
    val responsivenessEpoch: String = "NEW",
    val completedMatchupsInEpoch: Int = 0,
    val responsivenessEpochSequence: Long = 0L,
    val sourceType: String = "MANUAL",
    val externalId: String? = null,
    val normalizedTitle: String = "",
    val normalizedArtist: String = "",
    val tombstoneDeletedAt: Long? = null,
    val tombstoneSourceType: String? = null,
    val tombstoneExternalId: String? = null,
    val tombstoneScoreTenths: Int? = null,
    val tombstoneSeedElo: Double? = null,
    val tombstoneSuppressedExternalId: String? = null,
    val tombstoneSuppressedSourceType: String? = null,
    val tombstoneSuppressedNormalizedTitle: String? = null,
    val tombstoneSuppressedNormalizedArtist: String? = null
)

@Entity(tableName = "app_stats")
data class AppStatsEntity(
    @PrimaryKey val id: Int = 0,
    val matchCount: Int = 0,
    val skipCount: Int = 0
)

@Entity(tableName = "matchup_events")
data class MatchupEventEntity(
    @PrimaryKey val sequenceId: Long,
    val occurredAt: Long,
    val firstSubjectId: String,
    val secondSubjectId: String,
    val outcome: String,
    val winnerSubjectId: String? = null,
    val loserSubjectId: String? = null,
    val winnerEffectiveK: Double? = null,
    val loserEffectiveK: Double? = null
)

@Entity(tableName = "ranking_settings")
data class RankingSettingsEntity(
    @PrimaryKey val id: Int = 0,
    val autoPlayMatchupPreviews: Boolean = true,
    val showTips: Boolean = true,
    val presentation: String = "GRID",
    val metadataRetrievalEnabled: Boolean = true
)

@Entity(tableName = "import_batches")
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val sourceLabel: String,
    val importedAt: Long,
    val itemCount: Int
)

@Entity(tableName = "suggestion_dismissals")
data class SuggestionDismissalEntity(
    @PrimaryKey val subjectId: String,
    val dismissedAtSequenceId: Long,
    val dismissedScoreTenths: Int
)

data class SongWithStatsEntity(
    @Embedded val song: SongEntity,
    @Relation(parentColumn = "rankingSubjectId", entityColumn = "id")
    val stats: RankingSubjectEntity
)

@Entity(tableName = "albums", indices = [Index(value = ["matchStatus"])])
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val normalizedTitle: String = "",
    val normalizedArtist: String = "",
    val providerSourceType: String = "ITUNES",
    val providerCollectionId: String? = null,
    val providerTrackCount: Int? = null,
    val matchStatus: String = "PENDING",
    val matchConfidence: Double? = null,
    val createdAt: Long,
    val lastMatchAttemptAt: Long? = null,
    val lastMatchedAt: Long? = null
)

@Entity(tableName = "album_track_exclusions", indices = [Index(value = ["albumId"])])
data class AlbumTrackExclusionEntity(
    @PrimaryKey val songId: String,
    val albumId: String,
    val excludedAt: Long
)

@Entity(tableName = "album_missing_tracks", primaryKeys = ["albumId", "providerTrackId"])
data class AlbumMissingTrackEntity(
    val albumId: String,
    val providerTrackId: String,
    val title: String,
    val trackNumber: Int? = null,
    val artworkUrl: String? = null
)
