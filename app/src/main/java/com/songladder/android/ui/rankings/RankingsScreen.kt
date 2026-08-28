package com.songladder.android.ui.rankings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songladder.android.R
import com.songladder.android.domain.model.RankingPresentation
import com.songladder.android.domain.model.Song

@Composable
fun RankingsScreen(
    viewModel: RankingsViewModel,
    onOpenSettings: () -> Unit = {},
    onAddSongs: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = uiState.searchActive) {
        viewModel.setSearchActive(false)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopPreview() }
    }

    RankingsScreenContent(
        uiState = uiState,
        onTabSelected = viewModel::selectTab,
        onSearchActiveChanged = viewModel::setSearchActive,
        onSearchQueryChanged = viewModel::updateSearchQuery,
        onPresentationChanged = viewModel::setPresentation,
        onToggleUnrated = viewModel::toggleUnratedExpanded,
        onToggleIncompleteAlbums = viewModel::toggleIncompleteAlbumsExpanded,
        onToggleStats = viewModel::toggleStats,
        onTogglePreview = viewModel::togglePreview,
        onShowDetails = viewModel::showDetails,
        onHideDetails = viewModel::hideDetails,
        onSaveScore = viewModel::saveScore,
        onDismissTip = viewModel::dismissRankingsTip,
        onDeleteSong = viewModel::deleteSong,
        onUndoDelete = viewModel::undoDelete,
        onAcceptSuggestion = viewModel::acceptSuggestion,
        onDismissSuggestion = viewModel::dismissSuggestionLater,
        onToggleSuggestionSelection = viewModel::toggleSuggestionSelection,
        onClearSuggestionSelection = viewModel::clearSuggestionSelection,
        onAcceptSelectedSuggestions = viewModel::acceptSelectedSuggestions,
        onShowAlbumDetails = viewModel::showAlbumDetails,
        onHideAlbumDetails = viewModel::hideAlbumDetails,
        onToggleAlbumTrackExcluded = viewModel::setAlbumTrackExcluded,
        onAddAlbumMissingTracks = viewModel::addAlbumMissingTracks,
        onChooseAlbumRelease = viewModel::chooseAlbumRelease,
        onRefreshAlbumMetadata = viewModel::refreshAlbumMetadata,
        onOpenSettings = onOpenSettings,
        onAddSongs = onAddSongs
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RankingsScreenContent(
    uiState: RankingsUiState,
    onTabSelected: (RankingsTab) -> Unit,
    onSearchActiveChanged: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onPresentationChanged: (RankingPresentation) -> Unit,
    onToggleUnrated: () -> Unit,
    onToggleIncompleteAlbums: () -> Unit,
    onToggleStats: (String) -> Unit,
    onTogglePreview: (String) -> Unit,
    onShowDetails: (String) -> Unit,
    onHideDetails: () -> Unit,
    onSaveScore: (String, Int) -> Unit,
    onDismissTip: () -> Unit,
    onDeleteSong: (Song) -> Unit,
    onUndoDelete: () -> Unit,
    onAcceptSuggestion: (String, Int) -> Unit,
    onDismissSuggestion: (String) -> Unit,
    onToggleSuggestionSelection: (String) -> Unit,
    onClearSuggestionSelection: () -> Unit,
    onAcceptSelectedSuggestions: () -> Unit,
    onShowAlbumDetails: (String) -> Unit,
    onHideAlbumDetails: () -> Unit,
    onToggleAlbumTrackExcluded: (String, String, Boolean) -> Unit,
    onAddAlbumMissingTracks: (String, List<String>) -> Unit,
    onChooseAlbumRelease: (String, String) -> Unit,
    onRefreshAlbumMetadata: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onAddSongs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                if (uiState.searchActive && uiState.selectedTab == RankingsTab.SONGS) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = onSearchQueryChanged,
                        singleLine = true,
                        label = { Text(stringResource(R.string.rankings_search_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(stringResource(R.string.rankings_title))
                }
            },
            actions = {
                if (uiState.selectedTab == RankingsTab.SONGS) {
                    IconButton(onClick = { onSearchActiveChanged(!uiState.searchActive) }) {
                        Icon(
                            imageVector = if (uiState.searchActive) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = stringResource(
                                if (uiState.searchActive) R.string.rankings_close_search else R.string.rankings_open_search
                            )
                        )
                    }
                    IconButton(
                        onClick = {
                            onPresentationChanged(
                                if (uiState.presentation == RankingPresentation.GRID) {
                                    RankingPresentation.LIST
                                } else {
                                    RankingPresentation.GRID
                                }
                            )
                        }
                    ) {
                        Icon(
                            imageVector = if (uiState.presentation == RankingPresentation.GRID) {
                                Icons.Rounded.List
                            } else {
                                Icons.Rounded.GridView
                            },
                            contentDescription = stringResource(
                                if (uiState.presentation == RankingPresentation.GRID) {
                                    R.string.rankings_show_list
                                } else {
                                    R.string.rankings_show_grid
                                }
                            )
                        )
                    }
                }
                IconButton(onClick = onAddSongs) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.rankings_open_library)
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.settings_title)
                    )
                }
            }
        )
        TabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
            RankingsTab.entries.forEach { tab ->
                Tab(
                    selected = uiState.selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    text = {
                        Text(
                            stringResource(
                                when (tab) {
                                    RankingsTab.SONGS -> R.string.rankings_tab_songs
                                    RankingsTab.ALBUMS -> R.string.rankings_tab_albums
                                    RankingsTab.ARTISTS -> R.string.rankings_tab_artists
                                }
                            )
                        )
                    }
                )
            }
        }
        RankingsStatusBanner(status = uiState.status, onUndoDelete = onUndoDelete)
        when (uiState.selectedTab) {
            RankingsTab.SONGS -> RankingsSongsContent(
                uiState = uiState,
                onToggleUnrated = onToggleUnrated,
                onToggleStats = onToggleStats,
                onTogglePreview = onTogglePreview,
                onShowDetails = onShowDetails,
                onSaveScore = onSaveScore,
                onDismissTip = onDismissTip,
                onAcceptSuggestion = onAcceptSuggestion,
                onDismissSuggestion = onDismissSuggestion,
                onToggleSuggestionSelection = onToggleSuggestionSelection,
                onClearSuggestionSelection = onClearSuggestionSelection,
                onAcceptSelectedSuggestions = onAcceptSelectedSuggestions,
                modifier = Modifier.weight(1f)
            )
            RankingsTab.ALBUMS -> RankingsAlbumsContent(
                uiState = uiState,
                onToggleIncompleteAlbums = onToggleIncompleteAlbums,
                onShowAlbumDetails = onShowAlbumDetails,
                modifier = Modifier.weight(1f)
            )
            RankingsTab.ARTISTS -> ComingSoonContent(modifier = Modifier.weight(1f))
        }
    }

    uiState.detailSong?.let { song ->
        val rank = uiState.rankedSongs.firstOrNull { it.song.id == song.id }?.rank
        SongDetailDialog(
            song = song,
            rank = rank,
            isSaving = uiState.isSavingScore,
            onDismiss = onHideDetails,
            onSaveScore = { scoreTenths -> onSaveScore(song.id, scoreTenths) },
            onDeleteSong = { onDeleteSong(song) }
        )
    }

    uiState.albumDetail?.let { detail ->
        val rank = uiState.rankedAlbums.firstOrNull { it.album.id == detail.album.id }?.rank
        AlbumDetailDialog(
            detail = detail,
            rank = rank,
            matchCandidates = uiState.albumMatchCandidates,
            onDismiss = onHideAlbumDetails,
            onToggleTrackExcluded = { songId, excluded -> onToggleAlbumTrackExcluded(detail.album.id, songId, excluded) },
            onAddMissingTracks = { providerTrackIds -> onAddAlbumMissingTracks(detail.album.id, providerTrackIds) },
            onChooseRelease = { collectionId -> onChooseAlbumRelease(detail.album.id, collectionId) },
            onRefreshMetadata = { onRefreshAlbumMetadata(detail.album.id) }
        )
    }
}

@Composable
private fun RankingsSongsContent(
    uiState: RankingsUiState,
    onToggleUnrated: () -> Unit,
    onToggleStats: (String) -> Unit,
    onTogglePreview: (String) -> Unit,
    onShowDetails: (String) -> Unit,
    onSaveScore: (String, Int) -> Unit,
    onDismissTip: () -> Unit,
    onAcceptSuggestion: (String, Int) -> Unit,
    onDismissSuggestion: (String) -> Unit,
    onToggleSuggestionSelection: (String) -> Unit,
    onClearSuggestionSelection: () -> Unit,
    onAcceptSelectedSuggestions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    var previousPresentation by remember { mutableStateOf(uiState.presentation) }
    var gridRatingSongId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingSuggestionSubjectId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.presentation) {
        if (previousPresentation != uiState.presentation) {
            val anchorSongId = when (previousPresentation) {
                RankingPresentation.GRID -> gridState.firstVisibleSongKey()
                RankingPresentation.LIST -> listState.firstVisibleSongKey()
            }
            when (uiState.presentation) {
                RankingPresentation.GRID -> anchorSongId
                    ?.let { uiState.gridIndexForSong(it) }
                    ?.let { gridState.scrollToItem(it) }
                RankingPresentation.LIST -> anchorSongId
                    ?.let { uiState.listIndexForSong(it) }
                    ?.let { listState.scrollToItem(it) }
            }
            previousPresentation = uiState.presentation
        }
    }

    if (uiState.rankedSongs.isEmpty() && uiState.unratedSongs.isEmpty()) {
        EmptyRankingsContent(modifier = modifier)
        return
    }

    val gridRatingSong = uiState.allSongs.firstOrNull { it.id == gridRatingSongId }
    LaunchedEffect(gridRatingSongId, gridRatingSong) {
        if (gridRatingSongId != null && gridRatingSong == null) {
            gridRatingSongId = null
        }
    }

    val suggestionCallbacks = SuggestionCallbacks(
        selectedIds = uiState.selectedSuggestionIds,
        isSaving = uiState.isSavingScore,
        onToggleSelection = onToggleSuggestionSelection,
        onAcceptSelected = onAcceptSelectedSuggestions,
        onClearSelection = onClearSuggestionSelection,
        onAccept = onAcceptSuggestion,
        onEdit = { row ->
            gridRatingSongId = null
            editingSuggestionSubjectId = row.suggestion.subjectId
        },
        onDismissLater = onDismissSuggestion
    )

    when (uiState.presentation) {
        RankingPresentation.GRID -> RankingsGrid(
            uiState = uiState,
            onToggleUnrated = onToggleUnrated,
            onTogglePreview = onTogglePreview,
            onShowDetails = onShowDetails,
            onEditScore = { songId ->
                editingSuggestionSubjectId = null
                gridRatingSongId = songId
            },
            onDismissTip = onDismissTip,
            suggestionCallbacks = suggestionCallbacks,
            gridState = gridState,
            modifier = modifier
        )
        RankingPresentation.LIST -> RankingsList(
            uiState = uiState,
            onToggleUnrated = onToggleUnrated,
            onToggleStats = onToggleStats,
            onTogglePreview = onTogglePreview,
            onShowDetails = onShowDetails,
            onSaveScore = onSaveScore,
            suggestionCallbacks = suggestionCallbacks,
            listState = listState,
            modifier = modifier
        )
    }

    val editingSuggestion = uiState.suggestionRows.firstOrNull { it.suggestion.subjectId == editingSuggestionSubjectId }
    LaunchedEffect(editingSuggestionSubjectId, editingSuggestion) {
        if (editingSuggestionSubjectId != null && editingSuggestion == null) {
            editingSuggestionSubjectId = null
        }
    }
    editingSuggestion?.let { row ->
        GridScoreEditorSheet(
            song = row.song.copy(scoreTenths = row.suggestion.suggestedScoreTenths),
            isSaving = uiState.isSavingScore,
            onDismiss = { editingSuggestionSubjectId = null },
            onSaveScore = { scoreTenths ->
                onAcceptSuggestion(row.suggestion.subjectId, scoreTenths)
                editingSuggestionSubjectId = null
            }
        )
    }

    gridRatingSong?.let { song ->
        GridScoreEditorSheet(
            song = song,
            isSaving = uiState.isSavingScore,
            onDismiss = { gridRatingSongId = null },
            onSaveScore = { scoreTenths ->
                onSaveScore(song.id, scoreTenths)
                gridRatingSongId = null
            }
        )
    }
}

data class SuggestionCallbacks(
    val selectedIds: Set<String>,
    val isSaving: Boolean,
    val onToggleSelection: (String) -> Unit,
    val onAcceptSelected: () -> Unit,
    val onClearSelection: () -> Unit,
    val onAccept: (String, Int) -> Unit,
    val onEdit: (SuggestionRow) -> Unit,
    val onDismissLater: (String) -> Unit
)

private fun LazyGridState.firstVisibleSongKey(): String? =
    layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { item ->
        (item.key as? String)?.takeIf { it != "hint" && it != "unrated-header" && it != "suggestions" }
    }

private fun LazyListState.firstVisibleSongKey(): String? =
    layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { item ->
        (item.key as? String)?.takeIf { it != "unrated-header" && it != "suggestions" }
    }

private fun RankingsUiState.gridIndexForSong(songId: String): Int? {
    val hintOffset = if (settings.showTips) 1 else 0
    val suggestionOffset = if (suggestionRows.isNotEmpty()) 1 else 0
    val rankedIndex = rankedSongs.indexOfFirst { it.song.id == songId }
    if (rankedIndex >= 0) return rankedIndex + hintOffset + suggestionOffset
    val unratedIndex = unratedSongs.indexOfFirst { it.id == songId }
    if (unratedIndex >= 0 && unratedExpanded) return rankedSongs.size + hintOffset + 1 + suggestionOffset + unratedIndex
    return null
}

private fun RankingsUiState.listIndexForSong(songId: String): Int? {
    val suggestionOffset = if (suggestionRows.isNotEmpty()) 1 else 0
    val rankedIndex = rankedSongs.indexOfFirst { it.song.id == songId }
    if (rankedIndex >= 0) return rankedIndex + suggestionOffset
    val unratedIndex = unratedSongs.indexOfFirst { it.id == songId }
    if (unratedIndex >= 0 && unratedExpanded) return rankedSongs.size + 1 + suggestionOffset + unratedIndex
    return null
}
