package com.songladder.android.ui.rankings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.songladder.android.R
import com.songladder.android.ui.components.ScoreBadge
import com.songladder.android.ui.components.SongArtwork
import com.songladder.android.ui.components.SongRatingControl

@Composable
internal fun RankingsList(
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
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
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
                    onCancel = {
                        draftScore = rankedSong.song.scoreTenths ?: 55
                        onToggleStats()
                    },
                    enabled = !isSaving
                )
            }
        }
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
