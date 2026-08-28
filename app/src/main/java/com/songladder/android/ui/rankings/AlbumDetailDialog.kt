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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
                        AlbumMatchCandidatesPicker(
                            state = matchCandidates,
                            ownedTrackCount = detail.tracks.size,
                            onChoose = onChooseRelease
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
                                onToggleExcluded = { excluded -> onToggleTrackExcluded(track.song.id, excluded) }
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

@Composable
private fun AlbumTrackListItem(
    track: AlbumTrackRow,
    onToggleExcluded: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val includedDescription = stringResource(R.string.rankings_album_track_included)
    val excludedDescription = stringResource(R.string.rankings_album_track_excluded)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = track.song.title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Checkbox(
            checked = !track.excludedFromAverage,
            onCheckedChange = { included -> onToggleExcluded(!included) },
            modifier = Modifier.semantics {
                contentDescription = if (track.excludedFromAverage) excludedDescription else includedDescription
            }
        )
        ScoreBadge(scoreTenths = track.song.scoreTenths, size = 32.dp)
    }
}

@Composable
private fun MissingTrackListItem(
    track: AlbumReleaseTrack,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = track.title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Button(onClick = onAdd) {
            Text(stringResource(R.string.rankings_album_add_track_action))
        }
    }
}
