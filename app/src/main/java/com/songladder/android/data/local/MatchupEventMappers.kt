package com.songladder.android.data.local

import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.MatchupEventExport

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
