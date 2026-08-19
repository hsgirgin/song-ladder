package com.songladder.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.songladder.android.R
import com.songladder.android.domain.model.Song
import com.songladder.android.ui.components.SongArtwork
import com.songladder.android.ui.components.SongRatingControl
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.VolumeOff

@Composable
fun ImportRatingQueueScreen(
    queue: ImportRatingQueueState,
    currentSong: Song?,
    showTips: Boolean,
    onDismissTips: () -> Unit,
    onDismiss: () -> Unit,
    onPreviewToggle: () -> Unit,
    onScoreChange: (Int) -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
    onViewRankings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(
                            if (queue.kind == ImportRatingQueueKind.PLAYLIST) {
                                R.string.library_queue_playlist_title
                            } else {
                                R.string.library_queue_single_title
                            }
                        ),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = stringResource(R.string.library_queue_progress_label, queue.currentIndex + 1, queue.songIds.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.library_queue_close)
                    )
                }
            }

            LinearProgressIndicator(
                progress = if (queue.completion != null || queue.songIds.isEmpty()) 1f
                else (queue.currentIndex + 1).toFloat() / queue.songIds.size.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )

            if (showTips && queue.completion == null) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.library_queue_tip),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        TextButton(onClick = onDismissTips) {
                            Text(stringResource(R.string.rankings_dismiss_tip))
                        }
                    }
                }
            }

            if (queue.completion != null) {
                QueueCompletionCard(
                    completion = queue.completion,
                    onDismiss = onDismiss,
                    onViewRankings = onViewRankings
                )
            } else {
                currentSong?.let { song ->
                    QueueSongCard(
                        song = song,
                        previewState = queue.previewState,
                        onPreviewToggle = onPreviewToggle
                    )
                    queue.errorMessage?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    SongRatingControl(
                        scoreTenths = queue.draftScoreTenths,
                        onScoreChange = onScoreChange,
                        onSave = onSave,
                        onCancel = { onScoreChange(song.scoreTenths ?: 55) },
                        enabled = !queue.isSaving,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = onSkip,
                        enabled = !queue.isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.rank_skip_for_now))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun QueueSongCard(
    song: Song,
    previewState: ImportRatingQueuePreviewState,
    onPreviewToggle: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SongArtwork(
                artworkUrl = song.artworkUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (song.album.isNotBlank()) {
                    Text(song.album, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(
                onClick = onPreviewToggle,
                modifier = Modifier.fillMaxWidth(),
                enabled = previewState != ImportRatingQueuePreviewState.Loading
            ) {
                val icon = when (previewState) {
                    ImportRatingQueuePreviewState.Playing -> Icons.Rounded.Pause
                    ImportRatingQueuePreviewState.Unavailable -> Icons.Rounded.VolumeOff
                    else -> Icons.Rounded.PlayArrow
                }
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(
                        when (previewState) {
                            ImportRatingQueuePreviewState.Playing -> R.string.library_queue_pause_preview
                            ImportRatingQueuePreviewState.Loading -> R.string.library_queue_loading_preview
                            ImportRatingQueuePreviewState.Unavailable -> R.string.library_queue_preview_unavailable
                            ImportRatingQueuePreviewState.Available -> R.string.library_queue_play_preview
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun QueueCompletionCard(
    completion: ImportRatingQueueCompletion,
    onDismiss: () -> Unit,
    onViewRankings: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.library_queue_complete_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = pluralStringResource(
                    R.plurals.library_queue_complete_summary,
                    completion.ratedCount,
                    completion.ratedCount,
                    completion.skippedCount
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onViewRankings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.library_queue_view_rankings))
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.library_queue_done))
            }
        }
    }
}
