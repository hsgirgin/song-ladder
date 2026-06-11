package com.songladder.android.ui.rank

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songladder.android.domain.model.Song
import com.songladder.android.ui.components.SongArtwork

@Composable
fun RankScreen(viewModel: RankViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Song Ladder", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("Rank tracks at full speed.", style = MaterialTheme.typography.headlineLarge)
            Text(uiState.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatChip("Songs", uiState.songs.size.toString())
                StatChip("Matchups", uiState.stats.matchCount.toString())
                StatChip("Skips", uiState.stats.skipCount.toString())
            }
        }

        item {
            val matchup = uiState.matchup
            if (matchup == null) {
                EmptyRankState()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SongBattleCard(
                        song = matchup.left,
                        accent = MaterialTheme.colorScheme.primary,
                        onChoose = { viewModel.rankWinner(matchup.left.id, matchup.right.id) }
                    )
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Button(onClick = viewModel::skip) {
                            Text("Skip matchup")
                        }
                    }
                    SongBattleCard(
                        song = matchup.right,
                        accent = MaterialTheme.colorScheme.secondary,
                        onChoose = { viewModel.rankWinner(matchup.right.id, matchup.left.id) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SongBattleCard(
    song: Song,
    accent: androidx.compose.ui.graphics.Color,
    onChoose: () -> Unit
) {
    Card(
        onClick = onChoose,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SongArtwork(
                artworkUrl = song.artworkUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )
            AssistChip(onClick = onChoose, label = { Text("Tap to win") })
            Text(song.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text(song.artist, style = MaterialTheme.typography.titleLarge)
            if (song.album.isNotBlank()) {
                Text(song.album, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RankMetaPill("Rating ${song.rating}", accent)
                RankMetaPill("${song.wins}W ${song.losses}L", MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun RankMetaPill(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Card(
        modifier = Modifier.size(width = 104.dp, height = 82.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun EmptyRankState() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No matchup yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "Build a pool from manual songs, sample packs, or Spotify import, then come back here to battle them out.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
