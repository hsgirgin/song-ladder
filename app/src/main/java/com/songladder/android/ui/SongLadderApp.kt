package com.songladder.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.songladder.android.domain.model.TombstoneImportAction
import com.songladder.android.domain.model.TombstoneImportResolution
import com.songladder.android.ui.library.AddSongSheet
import com.songladder.android.ui.library.ImportRatingQueueScreen
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
            matchupsContent = { onOpenSettings, onAddSongs ->
                RankScreen(
                    viewModel = rankViewModel,
                    onOpenSettings = onOpenSettings,
                    onAddSongs = onAddSongs
                )
            },
            rankingsContent = { onOpenSettings, onAddSongs ->
                RankingsScreen(
                    viewModel = rankingsViewModel,
                    onOpenSettings = onOpenSettings,
                    onAddSongs = onAddSongs
                )
            },
            settingsContent = { onDismiss ->
                SettingsDialog(
                    viewModel = settingsViewModel,
                    libraryViewModel = libraryViewModel,
                    onDismiss = onDismiss
                )
            },
            addSongsContent = { onDismiss ->
                AddSongSheet(
                    viewModel = libraryViewModel,
                    onDismiss = onDismiss
                )
            },
            libraryViewModel = libraryViewModel
        )
    }
}

@Composable
internal fun SongLadderAppContent(
    matchupsContent: @Composable (onOpenSettings: () -> Unit, onAddSongs: () -> Unit) -> Unit,
    rankingsContent: @Composable (onOpenSettings: () -> Unit, onAddSongs: () -> Unit) -> Unit,
    settingsContent: @Composable (onDismiss: () -> Unit) -> Unit,
    addSongsContent: @Composable (onDismiss: () -> Unit) -> Unit = {},
    libraryViewModel: LibraryViewModel? = null,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val backStack = navController.currentBackStackEntryAsState()
    val currentRoute = backStack.value?.destination?.route
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAddSongs by rememberSaveable { mutableStateOf(false) }
    val openSettings = { showSettings = true }
    val addSongs = { showAddSongs = true }
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
                matchupsContent(openSettings, addSongs)
            }
            composable(SongLadderDestination.Rankings.route) {
                rankingsContent(openSettings, addSongs)
            }
        }
    }
    if (showSettings) {
        settingsContent {
            showSettings = false
        }
    }
    if (showAddSongs) {
        addSongsContent {
            showAddSongs = false
        }
    }

    if (libraryViewModel != null) {
        val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()

        libraryUiState.ratingQueue?.let { queue ->
            ImportRatingQueueScreen(
                queue = queue,
                currentSong = libraryUiState.songs.firstOrNull { it.id == queue.currentSongId },
                showTips = libraryUiState.settings.showTips,
                onDismissTips = libraryViewModel::dismissTips,
                onDismiss = libraryViewModel::dismissRatingQueue,
                onPreviewToggle = libraryViewModel::toggleQueuePreview,
                onScoreChange = libraryViewModel::updateQueueDraftScore,
                onSave = libraryViewModel::saveQueueScore,
                onSkip = libraryViewModel::skipQueueSong,
                onViewRankings = {
                    libraryViewModel.dismissRatingQueue()
                    navigateToTopLevel(SongLadderDestination.Rankings)
                }
            )
        }

        libraryUiState.tombstoneConflict?.let { conflict ->
            AlertDialog(
                onDismissRequest = libraryViewModel::cancelTombstoneConflict,
                title = { Text(stringResource(com.songladder.android.R.string.library_restore_history_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(com.songladder.android.R.string.library_restore_history_message))
                        Text("${conflict.candidate.title} — ${conflict.candidate.artist}")
                    }
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        conflict.matches.forEach { match ->
                            TextButton(
                                onClick = {
                                    libraryViewModel.resolveTombstoneConflict(
                                        TombstoneImportResolution(
                                            action = TombstoneImportAction.RESTORE,
                                            rankingSubjectId = match.rankingSubjectId
                                        )
                                    )
                                }
                            ) {
                                Text(
                                    stringResource(
                                        com.songladder.android.R.string.library_restore_history_action,
                                        match.title,
                                        match.artist
                                    )
                                )
                            }
                            TextButton(
                                onClick = {
                                    libraryViewModel.resolveTombstoneConflict(
                                        TombstoneImportResolution(
                                            action = TombstoneImportAction.START_FRESH,
                                            rankingSubjectId = match.rankingSubjectId
                                        )
                                    )
                                }
                            ) {
                                Text(stringResource(com.songladder.android.R.string.library_start_fresh_action))
                            }
                        }
                        TextButton(onClick = libraryViewModel::cancelTombstoneConflict) {
                            Text(stringResource(com.songladder.android.R.string.library_cancel_import_action))
                        }
                    }
                }
            )
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
