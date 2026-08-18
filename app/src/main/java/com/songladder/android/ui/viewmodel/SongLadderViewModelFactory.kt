package com.songladder.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.songladder.android.data.AppContainer
import com.songladder.android.ui.library.LibraryViewModel
import com.songladder.android.ui.rank.RankViewModel
import com.songladder.android.ui.rankings.RankingsViewModel
import com.songladder.android.ui.settings.SettingsViewModel

class SongLadderViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(RankViewModel::class.java) -> {
                RankViewModel(
                    container.songRepository,
                    container.rankingRepository,
                    container.songPreviewResolver,
                    container.songPreviewPlayer
                ) as T
            }
            modelClass.isAssignableFrom(LibraryViewModel::class.java) -> {
                LibraryViewModel(
                    songRepository = container.songRepository,
                    importRepository = container.importRepository,
                    musicSourceClient = container.musicSourceClient,
                    playlistSourceClient = container.playlistSourceClient
                ) as T
            }
            modelClass.isAssignableFrom(RankingsViewModel::class.java) -> {
                RankingsViewModel(
                    songRepository = container.songRepository,
                    rankingRepository = container.rankingRepository,
                    settingsRepository = container.settingsRepository,
                    songPreviewResolver = container.songPreviewResolver,
                    songPreviewPlayer = container.songPreviewPlayer
                ) as T
            }
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    rankingRepository = container.rankingRepository
                ) as T
            }
            else -> error("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
