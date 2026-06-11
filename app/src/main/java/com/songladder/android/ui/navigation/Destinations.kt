package com.songladder.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.ui.graphics.vector.ImageVector

sealed class SongLadderDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Rank : SongLadderDestination("rank", "Rank", Icons.Rounded.Speed)
    data object Library : SongLadderDestination("library", "Library", Icons.Rounded.LibraryMusic)
    data object Leaderboard : SongLadderDestination("leaderboard", "Leaderboard", Icons.Rounded.Equalizer)
}

val topLevelDestinations = listOf(
    SongLadderDestination.Rank,
    SongLadderDestination.Library,
    SongLadderDestination.Leaderboard
)
