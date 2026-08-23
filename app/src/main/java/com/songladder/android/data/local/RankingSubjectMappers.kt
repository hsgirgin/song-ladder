package com.songladder.android.data.local

import com.songladder.android.domain.model.RankingSubject
import com.songladder.android.domain.model.RankingSubjectExport
import com.songladder.android.domain.model.Tombstone
import com.songladder.android.domain.model.TombstoneExport

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
