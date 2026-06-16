package com.songladder.android.ui.rank

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songladder.android.domain.model.Song
import com.songladder.android.ui.components.SongArtwork

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
            .padding(horizontal = 20.dp)
            .padding(top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RankSessionHeader(uiState = uiState)

        AnimatedVisibility(
            visible = uiState.sessionFeedback != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            FeedbackBanner(
                message = uiState.sessionFeedback.orEmpty(),
                streakCount = uiState.streakCount
            )
        }

        if (!uiState.isReady || matchup == null) {
            EmptyRankState(onOpenLibrary = onOpenLibrary)
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val compact = maxHeight < 620.dp
                val artworkSize = if (compact) 96.dp else 112.dp
                val chipSpacing = if (compact) 8.dp else 10.dp

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SongBattleCard(
                        modifier = Modifier.weight(1f),
                        song = matchup.left,
                        accent = MaterialTheme.colorScheme.primary,
                        artworkSize = artworkSize,
                        chipSpacing = chipSpacing,
                        onChoose = { viewModel.rankWinner(matchup.left.id, matchup.right.id) }
                    )
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        FilledTonalButton(onClick = viewModel::skip) {
                            Text("Skip matchup")
                        }
                    }
                    SongBattleCard(
                        modifier = Modifier.weight(1f),
                        song = matchup.right,
                        accent = MaterialTheme.colorScheme.secondary,
                        artworkSize = artworkSize,
                        chipSpacing = chipSpacing,
                        onChoose = { viewModel.rankWinner(matchup.right.id, matchup.left.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RankSessionHeader(uiState: RankUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Ranking Session", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(uiState.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SessionChip("Songs ${uiState.songs.size}")
                SessionChip("Battles ${uiState.stats.matchCount}")
                SessionChip(
                    if (uiState.streakCount >= 2) "Streak ${uiState.streakCount}"
                    else "Skips ${uiState.stats.skipCount}"
                )
            }
        }
    }
}

@Composable
private fun FeedbackBanner(message: String, streakCount: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            if (streakCount >= 2) {
                Text("Win streak x$streakCount", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SongBattleCard(
    modifier: Modifier = Modifier,
    song: Song,
    accent: Color,
    artworkSize: androidx.compose.ui.unit.Dp,
    chipSpacing: androidx.compose.ui.unit.Dp,
    onChoose: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "battleCardScale"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(28.dp))
            .clickable(onClick = onChoose),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SongArtwork(
                artworkUrl = song.artworkUrl,
                modifier = Modifier.size(artworkSize)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(onClick = onChoose, label = { Text("Tap to win") })
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (song.album.isNotBlank()) {
                    Text(
                        text = song.album,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(chipSpacing)) {
                    RankMetaPill("Rating ${song.rating}", accent)
                    RankMetaPill("${song.wins}W ${song.losses}L", MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RankMetaPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SessionChip(text: String) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun EmptyRankState(onOpenLibrary: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("No matchup yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "Add at least two songs in Library, then come back here to start a fast ranking session.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onOpenLibrary) {
                Text("Open Library")
            }
        }
    }
}
