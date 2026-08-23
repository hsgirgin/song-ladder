package com.songladder.android.data.local

import com.songladder.android.domain.model.MatchupOutcome
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.RankingPresentation
import com.songladder.android.domain.model.ResponsivenessEpoch

internal fun String.toMusicSourceType(): MusicSourceType =
    runCatching { MusicSourceType.valueOf(trim().uppercase()) }
        .getOrDefault(MusicSourceType.IMPORT)

internal fun String.toResponsivenessEpoch(): ResponsivenessEpoch =
    runCatching { ResponsivenessEpoch.valueOf(trim().uppercase()) }
        .getOrDefault(ResponsivenessEpoch.NEW)

internal fun String.toMatchupOutcome(): MatchupOutcome =
    runCatching { MatchupOutcome.valueOf(trim().uppercase()) }
        .getOrDefault(MatchupOutcome.UNKNOWN)

internal fun String.toRankingPresentation(): RankingPresentation =
    runCatching { RankingPresentation.valueOf(trim().uppercase()) }
        .getOrDefault(RankingPresentation.GRID)
