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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
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
        SongLadderAppContent(
            matchupsContent = { onOpenSettings, onOpenLibrary ->
                RankScreen(
                    viewModel = rankViewModel,
                    onOpenSettings = onOpenSettings,
                    onOpenLibrary = onOpenLibrary
                )
            },
            libraryContent = { onOpenSettings ->
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onOpenSettings = onOpenSettings
                )
            },
            rankingsContent = { onOpenSettings ->
                RankingsScreen(
                    viewModel = rankingsViewModel,
                    onOpenSettings = onOpenSettings
                )
            },
            settingsContent = { onDismiss ->
                SettingsDialog(
                    viewModel = settingsViewModel,
                    onDismiss = onDismiss
                )
            }
        )
    }
}

@Composable
internal fun SongLadderAppContent(
    matchupsContent: @Composable (onOpenSettings: () -> Unit, onOpenLibrary: () -> Unit) -> Unit,
    libraryContent: @Composable (onOpenSettings: () -> Unit) -> Unit,
    rankingsContent: @Composable (onOpenSettings: () -> Unit) -> Unit,
    settingsContent: @Composable (onDismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val backStack = navController.currentBackStackEntryAsState()
    val currentRoute = backStack.value?.destination?.route
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val openSettings = { showSettings = true }
    val navigateToTopLevel: (SongLadderDestination) -> Unit = { destination ->
        navController.navigateToTopLevelDestination(destination, currentRoute)
    }

    SongLadderScaffold(
        currentRoute = currentRoute,
        isImeVisible = isImeVisible,
        onDestinationSelected = navigateToTopLevel
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = SongLadderDestination.Matchups.route,
            modifier = modifier.padding(innerPadding)
        ) {
            composable(SongLadderDestination.Matchups.route) {
                matchupsContent(openSettings) {
                    navigateToTopLevel(SongLadderDestination.Library)
                }
            }
            composable(SongLadderDestination.Library.route) {
                libraryContent(openSettings)
            }
            composable(SongLadderDestination.Rankings.route) {
                rankingsContent(openSettings)
            }
        }
    }
    if (showSettings) {
        settingsContent {
            showSettings = false
        }
    }
}

private fun NavHostController.navigateToTopLevelDestination(
    destination: SongLadderDestination,
    currentRoute: String?
) {
    if (currentRoute == destination.route) return
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
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
