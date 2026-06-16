package com.songladder.android.ui.rank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RankUiState(
    val songs: List<Song> = emptyList(),
    val stats: AppStats = AppStats(),
    val matchup: Matchup? = null,
    val message: String = "",
    val isReady: Boolean = false,
    val sessionFeedback: String? = null,
    val streakCount: Int = 0
)

private data class RankSessionState(
    val previousMatchup: Matchup? = null,
    val sessionFeedback: String? = null,
    val streakCount: Int = 0
)

class RankViewModel(
    private val songRepository: SongRepository,
    private val rankingRepository: RankingRepository
) : ViewModel() {
    private val matchupEngine = EloMatchupEngine()
    private val sessionState = MutableStateFlow(RankSessionState())
    private var clearFeedbackJob: Job? = null

    val uiState: StateFlow<RankUiState> = combine(
        songRepository.observeSongs(),
        rankingRepository.observeStats(),
        sessionState
    ) { songs, stats, session ->
        val matchup = matchupEngine.pickMatchup(songs, session.previousMatchup)
        RankUiState(
            songs = songs,
            stats = stats,
            matchup = matchup,
            message = when {
                songs.size < 2 -> "Add at least two songs to start ranking."
                session.streakCount >= 3 -> "Hot streak. Keep the ladder moving."
                else -> "Choose the better track and keep momentum up."
            },
            isReady = songs.size >= 2,
            sessionFeedback = session.sessionFeedback,
            streakCount = session.streakCount
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankUiState())

    fun rankWinner(winnerId: String, loserId: String) {
        viewModelScope.launch {
            val currentMatchup = uiState.value.matchup ?: return@launch
            val winnerTitle = sequenceOf(currentMatchup.left, currentMatchup.right)
                .firstOrNull { it.id == winnerId }
                ?.title
                ?: "Song picked"

            rankingRepository.recordBattle(winnerId, loserId)
            sessionState.update {
                it.copy(
                    previousMatchup = currentMatchup,
                    sessionFeedback = "Picked $winnerTitle",
                    streakCount = it.streakCount + 1
                )
            }
            scheduleFeedbackClear()
        }
    }

    fun skip() {
        viewModelScope.launch {
            val currentMatchup = uiState.value.matchup ?: return@launch
            rankingRepository.recordSkip(currentMatchup.left.id)
            sessionState.update {
                it.copy(
                    previousMatchup = currentMatchup,
                    sessionFeedback = "Matchup skipped",
                    streakCount = 0
                )
            }
            scheduleFeedbackClear()
        }
    }

    private fun scheduleFeedbackClear() {
        clearFeedbackJob?.cancel()
        clearFeedbackJob = viewModelScope.launch {
            delay(1_500)
            sessionState.update { it.copy(sessionFeedback = null) }
        }
    }
}
