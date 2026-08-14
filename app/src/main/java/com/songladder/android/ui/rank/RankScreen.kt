package com.songladder.android.ui.rank

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.Song
import com.songladder.android.ui.components.SongArtwork

internal enum class CardReaction {
    Idle,
    Winner,
    Loser,
    Skip
}

@Composable
fun RankScreen(
    viewModel: RankViewModel,
    onOpenLibrary: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val matchup = uiState.matchup
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, matchup?.left?.id, matchup?.right?.id) {
        fun updatePrefetchForLifecycle() {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                viewModel.updatePreviewPrefetch(matchup)
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> updatePrefetchForLifecycle()
                Lifecycle.Event.ON_STOP -> {
                    viewModel.stopPreview()
                    viewModel.clearPreviewPrefetch()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        updatePrefetchForLifecycle()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopPreview()
            viewModel.clearPreviewPrefetch()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        MinimalRankHeader(uiState = uiState)

        if (!uiState.isReady || matchup == null) {
            EmptyRankState(onOpenLibrary = onOpenLibrary)
        } else {
            RankMatchupContent(
                uiState = uiState,
                matchup = matchup,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onTogglePreview = viewModel::togglePreview,
                onChoose = viewModel::rankWinner,
                onSkip = viewModel::skip
            )
        }
    }
}

@Composable
internal fun RankMatchupContent(
    uiState: RankUiState,
    matchup: Matchup,
    modifier: Modifier = Modifier,
    onTogglePreview: (String) -> Unit,
    onChoose: (String, String) -> Unit,
    onSkip: () -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val compact = maxHeight < 640.dp || LocalDensity.current.fontScale > 1.3f
        val artworkSize = if (compact) 80.dp else 112.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier
                ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(com.songladder.android.R.string.rank_choose_prompt),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            MinimalSongChoiceCard(
                modifier = if (compact) Modifier.heightIn(min = 180.dp) else Modifier.weight(1f),
                song = matchup.left,
                artworkSize = artworkSize,
                reaction = uiState.visualFeedback.reactionFor(matchup.left.id),
                previewState = uiState.previews[matchup.left.id] ?: SongPreviewState.Loading,
                onTogglePreview = { onTogglePreview(matchup.left.id) },
                onChoose = { onChoose(matchup.left.id, matchup.right.id) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("·", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FilledTonalButton(onClick = onSkip) {
                    Text("Skip", style = MaterialTheme.typography.labelLarge)
                }
            }

            MinimalSongChoiceCard(
                modifier = if (compact) Modifier.heightIn(min = 180.dp) else Modifier.weight(1f),
                song = matchup.right,
                artworkSize = artworkSize,
                reaction = uiState.visualFeedback.reactionFor(matchup.right.id),
                previewState = uiState.previews[matchup.right.id] ?: SongPreviewState.Loading,
                onTogglePreview = { onTogglePreview(matchup.right.id) },
                onChoose = { onChoose(matchup.right.id, matchup.left.id) }
            )
        }
    }
}

@Composable
internal fun MinimalRankHeader(uiState: RankUiState) {
    val summaryParts = buildList {
        add(
            pluralStringResource(
                com.songladder.android.R.plurals.rank_song_count,
                uiState.songs.size,
                uiState.songs.size
            )
        )
        add(
            pluralStringResource(
                com.songladder.android.R.plurals.rank_match_count,
                uiState.stats.matchCount,
                uiState.stats.matchCount
            )
        )
        if (uiState.streakCount >= 2) {
            add(
                pluralStringResource(
                    com.songladder.android.R.plurals.rank_streak_count,
                    uiState.streakCount,
                    uiState.streakCount
                )
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("Rank", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            text = summaryParts.joinToString(
                separator = stringResource(com.songladder.android.R.string.rank_stat_separator)
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AnimatedVisibility(visible = uiState.message.isNotBlank()) {
            Text(
                text = uiState.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun MinimalSongChoiceCard(
    modifier: Modifier = Modifier,
    song: Song,
    artworkSize: androidx.compose.ui.unit.Dp,
    reaction: CardReaction,
    previewState: SongPreviewState,
    onTogglePreview: () -> Unit,
    onChoose: () -> Unit
) {
    val chooseLabel = stringResource(
        com.songladder.android.R.string.rank_choose_song_action,
        song.title,
        song.artist
    )
    var showStats by rememberSaveable(song.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = when (reaction) {
            CardReaction.Winner -> 1.02f
            CardReaction.Loser -> 0.98f
            CardReaction.Skip -> 0.985f
            CardReaction.Idle -> 1f
        },
        animationSpec = tween(durationMillis = 280),
        label = "rankCardScale"
    )
    val alpha by animateFloatAsState(
        targetValue = when (reaction) {
            CardReaction.Winner -> 1f
            CardReaction.Loser -> 0.62f
            CardReaction.Skip -> 0.78f
            CardReaction.Idle -> 1f
        },
        animationSpec = tween(durationMillis = 280),
        label = "rankCardAlpha"
    )
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = when (reaction) {
            CardReaction.Winner -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            CardReaction.Loser -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            CardReaction.Skip -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
            CardReaction.Idle -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        },
        animationSpec = tween(durationMillis = 280),
        label = "rankCardBorder"
    )
    val containerColor by androidx.compose.animation.animateColorAsState(
        targetValue = when (reaction) {
            CardReaction.Winner -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            CardReaction.Loser -> MaterialTheme.colorScheme.surface
            CardReaction.Skip -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            CardReaction.Idle -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(durationMillis = 280),
        label = "rankCardContainer"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .alpha(alpha)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable(
                onClickLabel = chooseLabel,
                role = Role.Button,
                onClick = onChoose
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SongArtwork(
                artworkUrl = song.artworkUrl,
                modifier = Modifier.size(artworkSize)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = artworkSize)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PreviewButton(
                        state = previewState,
                        songTitle = song.title,
                        onClick = onTogglePreview
                    )
                    IconButton(onClick = { showStats = !showStats }) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = if (showStats) "Hide song stats" else "Show song stats",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (previewState == SongPreviewState.Unavailable) {
                    Text(
                        text = "Preview unavailable",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!showStats && song.album.isNotBlank()) {
                        Spacer(modifier = Modifier.size(3.dp))
                        Text(
                            text = song.album,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                AnimatedVisibility(visible = showStats) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MinimalMetaPill("Rating ${song.rating}", MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        MinimalMetaPill("${song.wins}W ${song.losses}L", MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewButton(
    state: SongPreviewState,
    songTitle: String,
    onClick: () -> Unit
) {
    val enabled = state == SongPreviewState.Available || state == SongPreviewState.Playing
    IconButton(onClick = onClick, enabled = enabled) {
        when (state) {
            SongPreviewState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp
            )
            SongPreviewState.Available -> Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play preview of $songTitle"
            )
            SongPreviewState.Playing -> Icon(
                imageVector = Icons.Rounded.Pause,
                contentDescription = "Pause preview of $songTitle"
            )
            SongPreviewState.Unavailable -> Icon(
                imageVector = Icons.Rounded.MusicOff,
                contentDescription = "Preview unavailable for $songTitle"
            )
        }
    }
}

@Composable
private fun MinimalMetaPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EmptyRankState(onOpenLibrary: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("No matchup yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Button(onClick = onOpenLibrary) {
                Text("Open Library")
            }
        }
    }
}

private fun RankVisualFeedback.reactionFor(songId: String): CardReaction {
    return when (this) {
        RankVisualFeedback.None -> CardReaction.Idle
        is RankVisualFeedback.Choice -> when (songId) {
            winnerId -> CardReaction.Winner
            loserId -> CardReaction.Loser
            else -> CardReaction.Idle
        }

        RankVisualFeedback.Skip -> CardReaction.Skip
    }
}
