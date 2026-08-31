package com.songladder.android.ui.rankings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.songladder.android.R
import com.songladder.android.domain.model.AlbumDetail
import com.songladder.android.domain.model.AlbumMatchStatus
import com.songladder.android.domain.model.AlbumReleaseTrack
import com.songladder.android.domain.model.AlbumTrackRow
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.formatScoreTenths
import com.songladder.android.ui.components.MatchCandidateRow
import com.songladder.android.ui.components.ScoreBadge
import com.songladder.android.ui.components.SongArtwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumDetailDialog(
    detail: AlbumDetail,
    rank: Int?,
    matchCandidates: AlbumMatchCandidatesState?,
    previews: Map<String, RankingsPreviewState>,
    onDismiss: () -> Unit,
    onToggleTrackExcluded: (String, Boolean) -> Unit,
    onTogglePreview: (String) -> Unit,
    onToggleMissingTrackPreview: (AlbumReleaseTrack) -> Unit,
    onAddMissingTracks: (List<String>) -> Unit,
    onChooseRelease: (String) -> Unit,
    onRefreshMetadata: () -> Unit,
    onRequestChangeRelease: () -> Unit,
    onRateTrack: (Song) -> Unit,
    isSavingScore: Boolean = false
) {
    var showReleasePicker by remember(detail.album.id) {
        mutableStateOf(detail.album.matchStatus == AlbumMatchStatus.NEEDS_REVIEW)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(detail.album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Rounded.Close, contentDescription = stringResource(R.string.settings_done))
                        }
                    }
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SongArtwork(
                        artworkUrl = detail.album.artworkUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .aspectRatio(1f)
                    )
                    Text(detail.album.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (detail.scoreTenths != null) {
                            ScoreBadge(scoreTenths = detail.scoreTenths, size = 40.dp)
                            if (rank != null && rank > 0) {
                                Text(
                                    text = stringResource(R.string.rankings_rank_badge, rank),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            AlbumUnrankedBadge()
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (!showReleasePicker) {
                            TextButton(
                                onClick = {
                                    showReleasePicker = true
                                    onRequestChangeRelease()
                                }
                            ) {
                                Text(stringResource(R.string.rankings_album_change_release))
                            }
                        }
                        TextButton(onClick = onRefreshMetadata) {
                            Text(stringResource(R.string.rankings_album_refresh_metadata))
                        }
                    }

                    if (showReleasePicker) {
                        AlbumMatchCandidatesPicker(
                            state = matchCandidates,
                            ownedTrackCount = detail.tracks.size,
                            onChoose = { collectionId ->
                                showReleasePicker = false
                                onChooseRelease(collectionId)
                            }
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.rankings_album_tracks_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        detail.tracks.forEach { track ->
                            AlbumTrackListItem(
                                track = track,
                                previewState = previews[track.song.id],
                                onTogglePreview = { onTogglePreview(track.song.id) },
                                onToggleExcluded = { excluded -> onToggleTrackExcluded(track.song.id, excluded) },
                                onRate = { onRateTrack(track.song) },
                                rateEnabled = !isSavingScore
                            )
                        }
                    }

                    if (detail.missingTracks.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.rankings_album_missing_tracks_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            detail.missingTracks.forEach { track ->
                                MissingTrackListItem(
                                    track = track,
                                    previewState = previews[albumMissingTrackPreviewKey(track.trackId)],
                                    onTogglePreview = { onToggleMissingTrackPreview(track) },
                                    onAdd = { onAddMissingTracks(listOf(track.trackId)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumMatchCandidatesPicker(
    state: AlbumMatchCandidatesState?,
    ownedTrackCount: Int,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.rankings_album_candidates_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.rankings_album_candidates_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (ownedTrackCount > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.rankings_album_candidates_owned_count,
                    ownedTrackCount,
                    ownedTrackCount
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when {
            state == null || state.isLoading -> Text(
                text = stringResource(R.string.rankings_album_candidates_loading),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.error -> Text(
                text = stringResource(R.string.rankings_album_candidates_error),
                color = MaterialTheme.colorScheme.error
            )
            state.candidates.isEmpty() -> Text(
                text = stringResource(R.string.rankings_album_candidates_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> state.candidates.forEach { candidate ->
                val trackCountLabel = candidate.trackCount?.let { count ->
                    val countText = pluralStringResource(R.plurals.rankings_album_track_count, count, count)
                    if (ownedTrackCount > 0 && count == ownedTrackCount) {
                        stringResource(R.string.rankings_album_candidate_track_count_match, countText)
                    } else {
                        countText
                    }
                }
                MatchCandidateRow(
                    title = candidate.collectionName,
                    subtitle = candidate.artistName,
                    artworkUrl = candidate.artworkUrl,
                    trailingLabel = trackCountLabel,
                    actionLabel = stringResource(R.string.rankings_album_candidates_choose_action),
                    onChoose = { onChoose(candidate.collectionId) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackListItem(
    track: AlbumTrackRow,
    previewState: RankingsPreviewState?,
    onTogglePreview: () -> Unit,
    onToggleExcluded: (Boolean) -> Unit,
    onRate: () -> Unit,
    rateEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val previewActionLabel = stringResource(R.string.rankings_preview_action)
    val excludeActionLabel = stringResource(R.string.rankings_album_track_exclude_action)
    val includeActionLabel = stringResource(R.string.rankings_album_track_include_action)
    val menuActionLabel = if (track.excludedFromAverage) includeActionLabel else excludeActionLabel
    val scoreTenths = track.song.scoreTenths
    val rateLabel = scoreTenths?.let {
        stringResource(R.string.rankings_score_state, formatScoreTenths(it), track.song.title)
    } ?: stringResource(R.string.rankings_unrated_state)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTogglePreview,
                onClickLabel = previewActionLabel,
                onLongClick = { menuExpanded = true },
                onLongClickLabel = menuActionLabel
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(label = menuActionLabel) {
                        onToggleExcluded(!track.excludedFromAverage)
                        true
                    }
                )
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = track.song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (track.excludedFromAverage) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            PreviewLabel(state = previewState)
        }
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(enabled = rateEnabled, onClickLabel = rateLabel, onClick = onRate),
            contentAlignment = Alignment.Center
        ) {
            ScoreBadge(
                scoreTenths = scoreTenths,
                size = 32.dp,
                modifier = if (track.excludedFromAverage) Modifier.alpha(0.5f) else Modifier
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = menuActionLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(if (track.excludedFromAverage) includeActionLabel else excludeActionLabel) },
                    onClick = {
                        onToggleExcluded(!track.excludedFromAverage)
                        menuExpanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MissingTrackListItem(
    track: AlbumReleaseTrack,
    previewState: RankingsPreviewState?,
    onTogglePreview: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val previewActionLabel = stringResource(R.string.rankings_preview_action)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onTogglePreview, onClickLabel = previewActionLabel),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = track.title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            PreviewLabel(state = previewState)
        }
        Button(onClick = onAdd) {
            Text(stringResource(R.string.rankings_album_add_track_action))
        }
    }
}
