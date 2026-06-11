package com.songladder.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.songladder.android.ui.SongLadderApp
import com.songladder.android.ui.leaderboard.LeaderboardViewModel
import com.songladder.android.ui.library.LibraryViewModel
import com.songladder.android.ui.rank.RankViewModel
import com.songladder.android.ui.viewmodel.SongLadderViewModelFactory

class MainActivity : ComponentActivity() {
    private val rankViewModel: RankViewModel by viewModels {
        SongLadderViewModelFactory((application as SongLadderApplication).container)
    }
    private val libraryViewModel: LibraryViewModel by viewModels {
        SongLadderViewModelFactory((application as SongLadderApplication).container)
    }
    private val leaderboardViewModel: LeaderboardViewModel by viewModels {
        SongLadderViewModelFactory((application as SongLadderApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SongLadderApp(
                rankViewModel = rankViewModel,
                libraryViewModel = libraryViewModel,
                leaderboardViewModel = leaderboardViewModel
            )
        }
    }
}
