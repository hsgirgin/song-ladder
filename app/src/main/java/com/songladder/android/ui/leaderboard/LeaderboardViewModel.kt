package com.songladder.android.ui.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class LeaderboardViewModel(
    songRepository: SongRepository
) : ViewModel() {
    val songs: StateFlow<List<Song>> = songRepository.observeSongs()
        .map { songs -> songs.sortedWith(compareByDescending<Song> { it.rating }.thenBy { it.title }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
