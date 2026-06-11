package com.songladder.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.songladder.android.data.AppContainer
import com.songladder.android.ui.leaderboard.LeaderboardViewModel
import com.songladder.android.ui.library.LibraryViewModel
import com.songladder.android.ui.rank.RankViewModel

class SongLadderViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(RankViewModel::class.java) -> {
                RankViewModel(container.songRepository, container.rankingRepository) as T
            }
            modelClass.isAssignableFrom(LibraryViewModel::class.java) -> {
                LibraryViewModel(
                    songRepository = container.songRepository,
                    importRepository = container.importRepository,
                    musicSourceClient = container.musicSourceClient
                ) as T
            }
            modelClass.isAssignableFrom(LeaderboardViewModel::class.java) -> {
                LeaderboardViewModel(container.songRepository) as T
            }
            else -> error("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
