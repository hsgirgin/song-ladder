package com.songladder.android.ui.rankings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songladder.android.R
import com.songladder.android.domain.model.RankingPresentation
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.formatScoreTenths
import com.songladder.android.ui.components.ScoreBadge
import com.songladder.android.ui.components.SongArtwork
import com.songladder.android.ui.components.SongRatingControl

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
            RankingsTab.ALBUMS,
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
        onEdit = { row -> editingSuggestionSubjectId = row.suggestion.subjectId },
        onDismissLater = onDismissSuggestion
    )

    when (uiState.presentation) {
        RankingPresentation.GRID -> RankingsGrid(
            uiState = uiState,
            onToggleUnrated = onToggleUnrated,
            onTogglePreview = onTogglePreview,
            onShowDetails = onShowDetails,
            onEditScore = { songId -> gridRatingSongId = songId },
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

@Composable
private fun RankingsGrid(
    uiState: RankingsUiState,
    onToggleUnrated: () -> Unit,
    onTogglePreview: (String) -> Unit,
    onShowDetails: (String) -> Unit,
    onEditScore: (String) -> Unit,
    onDismissTip: () -> Unit,
    suggestionCallbacks: SuggestionCallbacks,
    gridState: LazyGridState,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (uiState.settings.showTips) {
            item(key = "hint", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                RankingsGridHint(onDismiss = onDismissTip)
            }
        }
        if (uiState.suggestionRows.isNotEmpty()) {
            item(key = "suggestions", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                SuggestionsSection(rows = uiState.suggestionRows, callbacks = suggestionCallbacks)
            }
        }
        items(uiState.rankedSongs, key = { it.song.id }) { rankedSong ->
            RankingsGridCard(
                rankedSong = rankedSong,
                previewState = uiState.previews[rankedSong.song.id],
                isSaving = uiState.isSavingScore,
                onTogglePreview = { onTogglePreview(rankedSong.song.id) },
                onShowDetails = { onShowDetails(rankedSong.song.id) },
                onEditScore = { onEditScore(rankedSong.song.id) }
            )
        }
        item(key = "unrated-header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            UnratedHeader(
                count = uiState.unratedSongs.size,
                expanded = uiState.unratedExpanded,
                onToggle = onToggleUnrated
            )
        }
        if (uiState.unratedExpanded) {
            items(uiState.unratedSongs, key = { it.id }) { song ->
                RankingsGridCard(
                    rankedSong = RankedSong(rank = 0, song = song),
                    previewState = uiState.previews[song.id],
                    isSaving = uiState.isSavingScore,
                    onTogglePreview = { onTogglePreview(song.id) },
                    onShowDetails = { onShowDetails(song.id) },
                    onEditScore = { onEditScore(song.id) }
                )
            }
        }
    }
}

@Composable
private fun RankingsList(
    uiState: RankingsUiState,
    onToggleUnrated: () -> Unit,
    onToggleStats: (String) -> Unit,
    onTogglePreview: (String) -> Unit,
    onShowDetails: (String) -> Unit,
    onSaveScore: (String, Int) -> Unit,
    suggestionCallbacks: SuggestionCallbacks,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (uiState.suggestionRows.isNotEmpty()) {
            item(key = "suggestions") {
                SuggestionsSection(rows = uiState.suggestionRows, callbacks = suggestionCallbacks)
            }
        }
        items(uiState.rankedSongs, key = { it.song.id }) { rankedSong ->
            RankingsListRow(
                rankedSong = rankedSong,
                previewState = uiState.previews[rankedSong.song.id],
                expanded = rankedSong.song.id in uiState.expandedSongIds,
                isSaving = uiState.isSavingScore,
                onToggleStats = { onToggleStats(rankedSong.song.id) },
                onTogglePreview = { onTogglePreview(rankedSong.song.id) },
                onShowDetails = { onShowDetails(rankedSong.song.id) },
                onSaveScore = { scoreTenths -> onSaveScore(rankedSong.song.id, scoreTenths) }
            )
        }
        item(key = "unrated-header") {
            UnratedHeader(
                count = uiState.unratedSongs.size,
                expanded = uiState.unratedExpanded,
                onToggle = onToggleUnrated
            )
        }
        if (uiState.unratedExpanded) {
            items(uiState.unratedSongs, key = { it.id }) { song ->
                RankingsListRow(
                    rankedSong = RankedSong(rank = 0, song = song),
                    previewState = uiState.previews[song.id],
                    expanded = song.id in uiState.expandedSongIds,
                    isSaving = uiState.isSavingScore,
                    onToggleStats = { onToggleStats(song.id) },
                    onTogglePreview = { onTogglePreview(song.id) },
                    onShowDetails = { onShowDetails(song.id) },
                    onSaveScore = { scoreTenths -> onSaveScore(song.id, scoreTenths) }
                )
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RankingsGridCard(
    rankedSong: RankedSong,
    previewState: RankingsPreviewState?,
    isSaving: Boolean,
    onTogglePreview: () -> Unit,
    onShowDetails: () -> Unit,
    onEditScore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val detailsLabel = stringResource(R.string.rankings_open_details)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTogglePreview,
                onLongClick = onShowDetails,
                onClickLabel = stringResource(R.string.rankings_preview_action),
                onLongClickLabel = detailsLabel
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(label = detailsLabel) {
                        onShowDetails()
                        true
                    }
                )
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                SongArtwork(
                    artworkUrl = rankedSong.song.artworkUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
                RankBadge(
                    rank = rankedSong.rank,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
                PreviewBadge(
                    state = previewState,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = rankedSong.song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = rankedSong.song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                GridScoreButton(
                    song = rankedSong.song,
                    enabled = !isSaving,
                    onClick = onEditScore
                )
            }
        }
    }
}

@Composable
private fun GridScoreButton(
    song: Song,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val score = song.scoreTenths?.let { formatScoreTenths(it) }
    val scoreStateDescription = score?.let { stringResource(R.string.rankings_score_state, it, song.title) }
        ?: stringResource(R.string.rankings_unrated_state)
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(4.dp),
        modifier = modifier
            .semantics {
                stateDescription = scoreStateDescription
            }
    ) {
        ScoreBadge(scoreTenths = song.scoreTenths)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridScoreEditorSheet(
    song: Song,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSaveScore: (Int) -> Unit
) {
    var draftScore by rememberSaveable(song.id, song.scoreTenths) {
        mutableIntStateOf(song.scoreTenths ?: 55)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.rankings_edit_score),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SongRatingControl(
                scoreTenths = draftScore,
                onScoreChange = { draftScore = it },
                onSave = { onSaveScore(draftScore) },
                onCancel = onDismiss,
                enabled = !isSaving
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RankingsListRow(
    rankedSong: RankedSong,
    previewState: RankingsPreviewState?,
    expanded: Boolean,
    isSaving: Boolean,
    onToggleStats: () -> Unit,
    onTogglePreview: () -> Unit,
    onShowDetails: () -> Unit,
    onSaveScore: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val detailsLabel = stringResource(R.string.rankings_open_details)
    var draftScore by rememberSaveable(rankedSong.song.id, rankedSong.song.scoreTenths) {
        mutableIntStateOf(rankedSong.song.scoreTenths ?: 55)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTogglePreview,
                onLongClick = onShowDetails,
                onClickLabel = stringResource(R.string.rankings_preview_action),
                onLongClickLabel = detailsLabel
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(label = detailsLabel) {
                        onShowDetails()
                        true
                    }
                )
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SongArtwork(artworkUrl = rankedSong.song.artworkUrl, modifier = Modifier.size(58.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (rankedSong.rank > 0) {
                            stringResource(R.string.rankings_ranked_title, rankedSong.rank, rankedSong.song.title)
                        } else {
                            rankedSong.song.title
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = rankedSong.song.artist,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    PreviewLabel(state = previewState)
                }
                FilledTonalButton(
                    onClick = onToggleStats,
                    contentPadding = PaddingValues(4.dp)
                ) {
                    ScoreBadge(scoreTenths = rankedSong.song.scoreTenths, size = 40.dp)
                }
                IconButton(onClick = onToggleStats) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.rankings_hide_stats else R.string.rankings_show_stats
                        )
                    )
                }
            }
            if (expanded) {
                Text(
                    text = stringResource(
                        R.string.rankings_stats_summary,
                        rankedSong.song.wins,
                        rankedSong.song.losses,
                        rankedSong.song.skips
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SongRatingControl(
                    scoreTenths = draftScore,
                    onScoreChange = { draftScore = it },
                    onSave = { onSaveScore(draftScore) },
                    onCancel = { draftScore = rankedSong.song.scoreTenths ?: 55 },
                    enabled = !isSaving
                )
            }
        }
    }
}

@Composable
private fun RankingsGridHint(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.rankings_grid_hint),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.rankings_dismiss_tip)
            )
        }
    }
}

@Composable
private fun SongDetailDialog(
    song: Song,
    rank: Int?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSaveScore: (Int) -> Unit,
    onDeleteSong: () -> Unit
) {
    var draftScore by rememberSaveable(song.id, song.scoreTenths) {
        mutableIntStateOf(song.scoreTenths ?: 55)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(song.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SongArtwork(
                    artworkUrl = song.artworkUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .aspectRatio(1f)
                )
                Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScoreBadge(scoreTenths = song.scoreTenths, size = 40.dp)
                    if (rank != null && rank > 0) {
                        Text(
                            text = stringResource(R.string.rankings_rank_badge, rank),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.rankings_stats_summary, song.wins, song.losses, song.skips),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SongRatingControl(
                    scoreTenths = draftScore,
                    onScoreChange = { draftScore = it },
                    onSave = { onSaveScore(draftScore) },
                    onCancel = { draftScore = song.scoreTenths ?: 55 },
                    enabled = !isSaving
                )
                TextButton(onClick = onDeleteSong, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.rankings_delete_song))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_done))
            }
        }
    )
}

@Composable
private fun UnratedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    FilledTonalButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(pluralStringResource(R.plurals.rankings_unrated_count, count, count))
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null
            )
        }
    }
}

@Composable
private fun SuggestionsSection(
    rows: List<SuggestionRow>,
    callbacks: SuggestionCallbacks,
    modifier: Modifier = Modifier
) {
    var showAcceptConfirm by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.rankings_suggestions_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            rows.forEach { row ->
                SuggestionRowCard(
                    row = row,
                    selected = row.suggestion.subjectId in callbacks.selectedIds,
                    enabled = !callbacks.isSaving,
                    onSelectedChange = { callbacks.onToggleSelection(row.suggestion.subjectId) },
                    onAccept = { callbacks.onAccept(row.suggestion.subjectId, row.suggestion.suggestedScoreTenths) },
                    onEdit = { callbacks.onEdit(row) },
                    onDismissLater = { callbacks.onDismissLater(row.suggestion.subjectId) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                TextButton(
                    onClick = callbacks.onClearSelection,
                    enabled = callbacks.selectedIds.isNotEmpty() && !callbacks.isSaving
                ) {
                    Text(stringResource(R.string.settings_clear_selection))
                }
                Button(
                    onClick = { showAcceptConfirm = true },
                    enabled = callbacks.selectedIds.isNotEmpty() && !callbacks.isSaving
                ) {
                    Text(
                        pluralStringResource(
                            R.plurals.rankings_accept_selected_suggestions_action,
                            callbacks.selectedIds.size,
                            callbacks.selectedIds.size
                        )
                    )
                }
            }
        }
    }

    if (showAcceptConfirm) {
        AlertDialog(
            onDismissRequest = { showAcceptConfirm = false },
            title = { Text(stringResource(R.string.rankings_confirm_accept_suggestions_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.rankings_confirm_accept_suggestions_message,
                        callbacks.selectedIds.size,
                        callbacks.selectedIds.size
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAcceptConfirm = false
                        callbacks.onAcceptSelected()
                    }
                ) {
                    Text(stringResource(R.string.rankings_confirm_accept_suggestions_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAcceptConfirm = false }) {
                    Text(stringResource(R.string.rating_editor_cancel))
                }
            }
        )
    }
}

@Composable
private fun SuggestionRowCard(
    row: SuggestionRow,
    selected: Boolean,
    enabled: Boolean,
    onSelectedChange: () -> Unit,
    onAccept: () -> Unit,
    onEdit: () -> Unit,
    onDismissLater: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = { onSelectedChange() }, enabled = enabled)
        ScoreBadge(scoreTenths = row.suggestion.suggestedScoreTenths, size = 36.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = pluralStringResource(
                    R.plurals.rankings_suggestion_comparison_count,
                    row.suggestion.comparisonCount,
                    row.suggestion.comparisonCount
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onEdit, enabled = enabled) {
            Text(stringResource(R.string.rankings_suggestion_edit))
        }
        TextButton(onClick = onDismissLater, enabled = enabled) {
            Text(stringResource(R.string.rank_skip_for_now))
        }
        Button(onClick = onAccept, enabled = enabled) {
            Text(stringResource(R.string.rankings_suggestion_accept))
        }
    }
}

@Composable
private fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    if (rank <= 0) return
    Text(
        text = stringResource(R.string.rankings_rank_badge, rank),
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.onPrimary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun PreviewBadge(state: RankingsPreviewState?, modifier: Modifier = Modifier) {
    val (icon, label) = when (state) {
        RankingsPreviewState.Playing -> Icons.Rounded.Pause to stringResource(R.string.rankings_pause_preview)
        RankingsPreviewState.Unavailable -> Icons.Rounded.MusicOff to stringResource(R.string.rankings_preview_unavailable)
        else -> Icons.Rounded.PlayArrow to stringResource(R.string.rankings_preview_action)
    }
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PreviewLabel(state: RankingsPreviewState?) {
    val text = when (state) {
        RankingsPreviewState.Playing -> stringResource(R.string.rankings_pause_preview)
        RankingsPreviewState.Unavailable -> stringResource(R.string.rankings_preview_unavailable)
        RankingsPreviewState.Loading -> stringResource(R.string.rankings_preview_loading)
        RankingsPreviewState.Available,
        null -> stringResource(R.string.rankings_preview_action)
    }
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RankingsStatusBanner(status: RankingsStatus, onUndoDelete: () -> Unit) {
    val text = when (status) {
        RankingsStatus.None -> return
        RankingsStatus.ComingSoon -> stringResource(R.string.rankings_coming_soon)
        RankingsStatus.SaveFailed -> stringResource(R.string.rankings_save_failed)
        RankingsStatus.DeleteFailed -> stringResource(R.string.rankings_delete_failed)
        RankingsStatus.UndoDeleteFailed -> stringResource(R.string.rankings_undo_delete_failed)
        is RankingsStatus.ScoreSaved -> stringResource(R.string.rankings_score_saved)
        is RankingsStatus.DeletedSong -> stringResource(R.string.rankings_song_deleted, status.title)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (status is RankingsStatus.DeletedSong) {
            TextButton(onClick = onUndoDelete) {
                Text(stringResource(R.string.rankings_undo_delete))
            }
        }
    }
}

@Composable
private fun EmptyRankingsContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rankings_empty_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.rankings_empty_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ComingSoonContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.rankings_coming_soon), style = MaterialTheme.typography.titleMedium)
    }
}

private fun LazyGridState.firstVisibleSongKey(): String? =
    layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { item ->
        (item.key as? String)?.takeIf { it != "hint" && it != "unrated-header" && it != "suggestions" }
    }

private fun LazyListState.firstVisibleSongKey(): String? =
    layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { item ->
        (item.key as? String)?.takeIf { it != "unrated-header" && it != "suggestions" }
    }

private fun RankingsUiState.gridIndexForSong(songId: String): Int? {
    val suggestionOffset = if (suggestionRows.isNotEmpty()) 1 else 0
    val rankedIndex = rankedSongs.indexOfFirst { it.song.id == songId }
    if (rankedIndex >= 0) return rankedIndex + 1 + suggestionOffset
    val unratedIndex = unratedSongs.indexOfFirst { it.id == songId }
    if (unratedIndex >= 0 && unratedExpanded) return rankedSongs.size + 2 + suggestionOffset + unratedIndex
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
