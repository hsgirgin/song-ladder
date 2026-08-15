package com.songladder.android.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.songladder.android.domain.model.BASE_RATING

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val externalId: String? = null,
    val sourceType: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val createdAt: Long
)

@Entity(
    tableName = "ranking_stats",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("songId")]
)
data class RankingStatsEntity(
    @PrimaryKey val songId: String,
    val rating: Int = BASE_RATING,
    val wins: Int = 0,
    val losses: Int = 0,
    val skips: Int = 0,
    val lastRankedAt: Long? = null
)

@Entity(tableName = "app_stats")
data class AppStatsEntity(
    @PrimaryKey val id: Int = 0,
    val matchCount: Int = 0,
    val skipCount: Int = 0,
    val dailyGoalDate: String? = null,
    val dailyMatchCount: Int = 0
)

@Entity(tableName = "import_batches")
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val sourceLabel: String,
    val importedAt: Long,
    val itemCount: Int
)

data class SongWithStatsEntity(
    @Embedded val song: SongEntity,
    @Relation(parentColumn = "id", entityColumn = "songId")
    val stats: RankingStatsEntity
)
