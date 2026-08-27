package com.songladder.android.data.local

import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.SuggestionDismissal

fun SuggestionDismissalEntity.toDomain(): SuggestionDismissal = SuggestionDismissal(
    subjectId = subjectId,
    dismissedAtSequenceId = dismissedAtSequenceId,
    dismissedScoreTenths = dismissedScoreTenths
)

fun SuggestionDismissal.toEntity(): SuggestionDismissalEntity = SuggestionDismissalEntity(
    subjectId = subjectId,
    dismissedAtSequenceId = dismissedAtSequenceId,
    dismissedScoreTenths = dismissedScoreTenths
)

fun RankingSettingsEntity.toDomain(): RankingSettings = RankingSettings(
    autoPlayMatchupPreviews = autoPlayMatchupPreviews,
    showTips = showTips,
    presentation = presentation.toRankingPresentation(),
    metadataRetrievalEnabled = metadataRetrievalEnabled
)

fun RankingSettings.toEntity(): RankingSettingsEntity = RankingSettingsEntity(
    autoPlayMatchupPreviews = autoPlayMatchupPreviews,
    showTips = showTips,
    presentation = presentation.name,
    metadataRetrievalEnabled = metadataRetrievalEnabled
)

fun AppStatsEntity?.toDomain(): AppStats = AppStats(
    matchCount = this?.matchCount ?: 0,
    skipCount = this?.skipCount ?: 0
)
