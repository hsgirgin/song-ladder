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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.songladder.android.ui.library.LibraryScreen
import com.songladder.android.ui.library.LibraryViewModel
import com.songladder.android.ui.navigation.SongLadderDestination
import com.songladder.android.ui.navigation.topLevelDestinations
import com.songladder.android.ui.rank.RankScreen
import com.songladder.android.ui.rank.RankViewModel
import com.songladder.android.ui.rankings.RankingsScreen
import com.songladder.android.ui.rankings.RankingsViewModel
import com.songladder.android.ui.settings.SettingsDialog
import com.songladder.android.ui.settings.SettingsViewModel
import com.songladder.android.ui.theme.SongLadderTheme

@Composable
fun SongLadderApp(
    rankViewModel: RankViewModel,
    libraryViewModel: LibraryViewModel,
    rankingsViewModel: RankingsViewModel,
    settingsViewModel: SettingsViewModel
) {
    SongLadderTheme {
        val navController = rememberNavController()
        val backStack = navController.currentBackStackEntryAsState()
        val currentRoute = backStack.value?.destination?.route
        val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        var showSettings by rememberSaveable { mutableStateOf(false) }

        SongLadderScaffold(
            currentRoute = currentRoute,
            isImeVisible = isImeVisible,
            onDestinationSelected = { destination ->
                if (currentRoute != destination.route) {
                    navController.navigate(destination.route) {
                        popUpTo(SongLadderDestination.Matchups.route) {
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
                startDestination = SongLadderDestination.Matchups.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(SongLadderDestination.Matchups.route) {
                    RankScreen(
                        viewModel = rankViewModel,
                        onOpenSettings = { showSettings = true },
                        onOpenLibrary = {
                            navController.navigate(SongLadderDestination.Library.route) {
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
                composable(SongLadderDestination.Library.route) {
                    LibraryScreen(
                        viewModel = libraryViewModel,
                        onOpenSettings = { showSettings = true }
                    )
                }
                composable(SongLadderDestination.Rankings.route) {
                    RankingsScreen(
                        viewModel = rankingsViewModel,
                        onOpenSettings = { showSettings = true }
                    )
                }
            }
        }
        if (showSettings) {
            SettingsDialog(
                viewModel = settingsViewModel,
                onDismiss = { showSettings = false }
            )
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
                        val label = stringResource(destination.labelRes)
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { onDestinationSelected(destination) },
                            icon = { Icon(destination.icon, contentDescription = label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        },
        content = content
    )
}
