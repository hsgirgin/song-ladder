package com.songladder.android.ui.rank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RankUiState(
    val songs: List<Song> = emptyList(),
    val stats: AppStats = AppStats(),
    val matchup: Matchup? = null,
    val message: String = "",
    val isReady: Boolean = false
)

class RankViewModel(
    private val songRepository: SongRepository,
    private val rankingRepository: RankingRepository
) : ViewModel() {
    private val matchupEngine = EloMatchupEngine()
    private val previousMatchup = MutableStateFlow<Matchup?>(null)

    val uiState: StateFlow<RankUiState> = combine(
        songRepository.observeSongs(),
        rankingRepository.observeStats(),
        previousMatchup
    ) { songs, stats, previous ->
        val matchup = matchupEngine.pickMatchup(songs, previous)
        RankUiState(
            songs = songs,
            stats = stats,
            matchup = matchup,
            message = if (songs.size < 2) {
                "Import or add at least two songs to start ranking."
            } else {
                "Choose the better track and keep the ladder moving."
            },
            isReady = songs.size >= 2
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankUiState())

    fun rankWinner(winnerId: String, loserId: String) {
        viewModelScope.launch {
            rankingRepository.recordBattle(winnerId, loserId)
            previousMatchup.value = uiState.value.matchup
        }
    }

    fun skip() {
        viewModelScope.launch {
            uiState.value.matchup?.left?.id?.let { songId ->
                rankingRepository.recordSkip(songId)
            }
            previousMatchup.value = uiState.value.matchup
        }
    }
}
