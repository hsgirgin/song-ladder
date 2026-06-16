package com.songladder.android.ui.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class LeaderboardSortMode {
    TOP_RATED,
    MOST_PLAYED,
    MOST_SKIPPED
}

data class LeaderboardUiState(
    val songs: List<Song> = emptyList(),
    val sortMode: LeaderboardSortMode = LeaderboardSortMode.TOP_RATED
)

class LeaderboardViewModel(
    songRepository: SongRepository
) : ViewModel() {
    private val sortMode = MutableStateFlow(LeaderboardSortMode.TOP_RATED)

    val uiState: StateFlow<LeaderboardUiState> = combine(
        songRepository.observeSongs(),
        sortMode
    ) { songs, mode ->
        LeaderboardUiState(
            songs = songs.sortedWith(mode.comparator()),
            sortMode = mode
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LeaderboardUiState())

    fun updateSortMode(mode: LeaderboardSortMode) {
        sortMode.value = mode
    }
}

private fun LeaderboardSortMode.comparator(): Comparator<Song> {
    return when (this) {
        LeaderboardSortMode.TOP_RATED -> compareByDescending<Song> { it.rating }.thenBy { it.title }
        LeaderboardSortMode.MOST_PLAYED -> compareByDescending<Song> { it.wins + it.losses + it.skips }.thenByDescending { it.rating }
        LeaderboardSortMode.MOST_SKIPPED -> compareByDescending<Song> { it.skips }.thenByDescending { it.rating }
    }
}
