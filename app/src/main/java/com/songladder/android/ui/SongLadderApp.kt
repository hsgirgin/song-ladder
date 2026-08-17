package com.songladder.android.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.songladder.android.ui.leaderboard.LeaderboardScreen
import com.songladder.android.ui.leaderboard.LeaderboardViewModel
import com.songladder.android.ui.library.LibraryScreen
import com.songladder.android.ui.library.LibraryViewModel
import com.songladder.android.ui.navigation.SongLadderDestination
import com.songladder.android.ui.navigation.topLevelDestinations
import com.songladder.android.ui.rank.RankScreen
import com.songladder.android.ui.rank.RankViewModel
import com.songladder.android.ui.theme.SongLadderTheme

@Composable
fun SongLadderApp(
    rankViewModel: RankViewModel,
    libraryViewModel: LibraryViewModel,
    leaderboardViewModel: LeaderboardViewModel
) {
    SongLadderTheme {
        val navController = rememberNavController()
        val backStack = navController.currentBackStackEntryAsState()
        val currentRoute = backStack.value?.destination?.route
        val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

        SongLadderScaffold(
            currentRoute = currentRoute,
            isImeVisible = isImeVisible,
            onDestinationSelected = { destination ->
                if (currentRoute != destination.route) {
                    navController.navigate(destination.route) {
                        popUpTo(SongLadderDestination.Rank.route) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = SongLadderDestination.Rank.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(SongLadderDestination.Rank.route) {
                    RankScreen(
                        viewModel = rankViewModel,
                        onOpenLibrary = {
                            navController.navigate(SongLadderDestination.Library.route) {
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
                composable(SongLadderDestination.Library.route) {
                    LibraryScreen(viewModel = libraryViewModel)
                }
                composable(SongLadderDestination.Leaderboard.route) {
                    LeaderboardScreen(viewModel = leaderboardViewModel)
                }
            }
        }
    }
}

@Composable
internal fun SongLadderScaffold(
    currentRoute: String?,
    isImeVisible: Boolean,
    onDestinationSelected: (SongLadderDestination) -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!isImeVisible) {
                NavigationBar {
                    topLevelDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { onDestinationSelected(destination) },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        },
        content = content
    )
}
