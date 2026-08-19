package com.songladder.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.ui.graphics.vector.ImageVector
import com.songladder.android.R

sealed class SongLadderDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
) {
    data object Matchups : SongLadderDestination("matchups", R.string.destination_matchups, Icons.Rounded.Speed)
    data object Rankings : SongLadderDestination("rankings", R.string.destination_rankings, Icons.Rounded.Equalizer)
}

val topLevelDestinations = listOf(
    SongLadderDestination.Matchups,
    SongLadderDestination.Rankings
)
