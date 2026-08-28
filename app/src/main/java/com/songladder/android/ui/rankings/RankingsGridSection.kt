package com.songladder.android.ui.rankings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.songladder.android.R
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.formatScoreTenths
import com.songladder.android.ui.components.ScoreBadge
import com.songladder.android.ui.components.SongArtwork
import com.songladder.android.ui.components.SongRatingControl

@Composable
internal fun RankingsGrid(
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
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                stateDescription = scoreStateDescription
            }
    ) {
        ScoreBadge(scoreTenths = song.scoreTenths)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GridScoreEditorSheet(
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
internal fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
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
