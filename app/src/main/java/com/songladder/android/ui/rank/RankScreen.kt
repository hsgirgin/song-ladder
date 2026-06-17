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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songladder.android.domain.model.Song
import com.songladder.android.ui.components.SongArtwork

private enum class CardReaction {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
            .padding(top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        MinimalRankHeader(uiState = uiState)

        if (!uiState.isReady || matchup == null) {
            EmptyRankState(onOpenLibrary = onOpenLibrary)
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val compact = maxHeight < 640.dp
                val artworkSize = if (compact) 96.dp else 112.dp
                val leftReaction = uiState.visualFeedback.reactionFor(matchup.left.id)
                val rightReaction = uiState.visualFeedback.reactionFor(matchup.right.id)

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MinimalSongChoiceCard(
                        modifier = Modifier.weight(1f),
                        song = matchup.left,
                        artworkSize = artworkSize,
                        reaction = leftReaction,
                        onChoose = { viewModel.rankWinner(matchup.left.id, matchup.right.id) }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("·", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        FilledTonalButton(onClick = viewModel::skip) {
                            Text("Skip", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    MinimalSongChoiceCard(
                        modifier = Modifier.weight(1f),
                        song = matchup.right,
                        artworkSize = artworkSize,
                        reaction = rightReaction,
                        onChoose = { viewModel.rankWinner(matchup.right.id, matchup.left.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MinimalRankHeader(uiState: RankUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("Rank", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(uiState.songs.size.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("·", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(uiState.stats.matchCount.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (uiState.streakCount >= 2) {
                Text("·", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(uiState.streakCount.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
private fun MinimalSongChoiceCard(
    modifier: Modifier = Modifier,
    song: Song,
    artworkSize: androidx.compose.ui.unit.Dp,
    reaction: CardReaction,
    onChoose: () -> Unit
) {
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
            .clickable(onClick = onChoose),
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
                    IconButton(onClick = { showStats = !showStats }) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = if (showStats) "Hide song stats" else "Show song stats",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
