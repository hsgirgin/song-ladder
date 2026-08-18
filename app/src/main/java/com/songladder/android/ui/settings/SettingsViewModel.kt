package com.songladder.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.domain.model.DeletedRankingHistory
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: RankingSettings = RankingSettings(),
    val deletedHistories: List<DeletedRankingHistory> = emptyList(),
    val selectedHistoryIds: Set<String> = emptySet(),
    val isDeletingHistory: Boolean = false,
    val status: SettingsStatus = SettingsStatus.None
)

sealed interface SettingsStatus {
    data object None : SettingsStatus
    data object SaveFailed : SettingsStatus
    data object DeleteFailed : SettingsStatus
    data class DeletedHistory(val eventCount: Int) : SettingsStatus
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val rankingRepository: RankingRepository
) : ViewModel() {
    private val localState = MutableStateFlow(SettingsUiState())

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.observeSettings(),
        rankingRepository.observeDeletedRankingHistories(),
        localState
    ) { settings, histories, local ->
        local.copy(
            settings = settings,
            deletedHistories = histories,
            selectedHistoryIds = local.selectedHistoryIds.intersect(histories.mapTo(mutableSetOf()) { it.rankingSubjectId })
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setAutoPlayMatchupPreviews(enabled: Boolean) {
        saveSettings(uiState.value.settings.copy(autoPlayMatchupPreviews = enabled))
    }

    fun showTipsAgain() {
        saveSettings(uiState.value.settings.copy(showTips = true))
    }

    fun toggleDeletedHistorySelection(rankingSubjectId: String) {
        localState.update { state ->
            state.copy(
                selectedHistoryIds = if (rankingSubjectId in state.selectedHistoryIds) {
                    state.selectedHistoryIds - rankingSubjectId
                } else {
                    state.selectedHistoryIds + rankingSubjectId
                },
                status = SettingsStatus.None
            )
        }
    }

    fun clearSelection() {
        localState.update { it.copy(selectedHistoryIds = emptySet(), status = SettingsStatus.None) }
    }

    fun deleteSelectedRankingHistory() {
        val selectedIds = uiState.value.selectedHistoryIds
        if (selectedIds.isEmpty() || uiState.value.isDeletingHistory) return
        localState.update { it.copy(isDeletingHistory = true, status = SettingsStatus.None) }
        viewModelScope.launch {
            val result = runCatching {
                selectedIds.sumOf { id ->
                    rankingRepository.deleteRankingHistory(id).getOrThrow().deletedEventCount
                }
            }
            localState.update { state ->
                result.fold(
                    onSuccess = { deletedCount ->
                        state.copy(
                            selectedHistoryIds = emptySet(),
                            isDeletingHistory = false,
                            status = SettingsStatus.DeletedHistory(deletedCount)
                        )
                    },
                    onFailure = {
                        state.copy(
                            isDeletingHistory = false,
                            status = SettingsStatus.DeleteFailed
                        )
                    }
                )
            }
        }
    }

    private fun saveSettings(settings: RankingSettings) {
        viewModelScope.launch {
            settingsRepository.saveSettings(settings).onFailure {
                localState.update { state ->
                    state.copy(status = SettingsStatus.SaveFailed)
                }
            }
        }
    }
}
