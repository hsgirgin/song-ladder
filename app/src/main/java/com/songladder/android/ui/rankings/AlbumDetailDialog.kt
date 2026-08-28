package com.songladder.android.ui.rankings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.songladder.android.R
import com.songladder.android.domain.model.AlbumDetail
import com.songladder.android.domain.model.AlbumMatchStatus
import com.songladder.android.domain.model.AlbumMissingTrack
import com.songladder.android.domain.model.AlbumTrackRow
import com.songladder.android.ui.components.MatchCandidateRow
import com.songladder.android.ui.components.ScoreBadge
import com.songladder.android.ui.components.SongArtwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumDetailDialog(
    detail: AlbumDetail,
    rank: Int?,
    matchCandidates: AlbumMatchCandidatesState?,
    onDismiss: () -> Unit,
    onToggleTrackExcluded: (String, Boolean) -> Unit,
    onAddMissingTracks: (List<String>) -> Unit,
    onChooseRelease: (String) -> Unit,
    onRefreshMetadata: () -> Unit
) {
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
                        TextButton(onClick = onRefreshMetadata) {
                            Text(stringResource(R.string.rankings_album_refresh_metadata))
                        }
                    }

                    if (detail.album.matchStatus == AlbumMatchStatus.NEEDS_REVIEW) {
                        AlbumMatchCandidatesPicker(state = matchCandidates, onChoose = onChooseRelease)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.rankings_album_tracks_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        detail.tracks.forEach { track ->
                            AlbumTrackListItem(
                                track = track,
                                onToggleExcluded = { excluded -> onToggleTrackExcluded(track.song.id, excluded) }
                            )
                        }
                    }

                    if (detail.missingTracks.isNotEmpty()) {
                        MissingTracksSection(
                            missingTracks = detail.missingTracks,
                            onAddSelected = onAddMissingTracks
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumMatchCandidatesPicker(
    state: AlbumMatchCandidatesState?,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.rankings_album_candidates_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
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
                MatchCandidateRow(candidate = candidate, onChoose = { onChoose(candidate.collectionId) })
            }
        }
    }
}

@Composable
private fun AlbumTrackListItem(
    track: AlbumTrackRow,
    onToggleExcluded: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = track.song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = !track.excludedFromAverage,
                    onCheckedChange = { included -> onToggleExcluded(!included) }
                )
                Text(
                    text = stringResource(R.string.rankings_album_track_included),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ScoreBadge(scoreTenths = track.song.scoreTenths, size = 32.dp)
    }
}

@Composable
private fun MissingTracksSection(
    missingTracks: List<AlbumMissingTrack>,
    onAddSelected: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedIds by rememberSaveable(missingTracks.map { it.providerTrackId }) {
        mutableStateOf(emptySet<String>())
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.rankings_album_missing_tracks_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        missingTracks.forEach { track ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = track.providerTrackId in selectedIds,
                    onCheckedChange = { checked ->
                        selectedIds = if (checked) selectedIds + track.providerTrackId else selectedIds - track.providerTrackId
                    }
                )
                Text(
                    text = track.title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Button(
            onClick = {
                onAddSelected(selectedIds.toList())
                selectedIds = emptySet()
            },
            enabled = selectedIds.isNotEmpty()
        ) {
            Text(
                pluralStringResource(
                    R.plurals.rankings_album_add_missing_tracks,
                    selectedIds.size,
                    selectedIds.size
                )
            )
        }
    }
}
