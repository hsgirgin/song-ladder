package com.songladder.android.ui.rankings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.songladder.android.R
import com.songladder.android.domain.model.Song
import com.songladder.android.ui.components.ScoreBadge
import com.songladder.android.ui.components.SongArtwork
import com.songladder.android.ui.components.SongRatingControl

@Composable
internal fun SongDetailDialog(
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
internal fun UnratedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
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
internal fun RankingsStatusBanner(status: RankingsStatus, onUndoDelete: () -> Unit) {
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
internal fun EmptyRankingsContent(modifier: Modifier = Modifier) {
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
internal fun ComingSoonContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.rankings_coming_soon), style = MaterialTheme.typography.titleMedium)
    }
}
