package com.songladder.android.ui.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songladder.android.domain.model.Song
import com.songladder.android.ui.components.SongArtwork

@Composable
fun LeaderboardScreen(viewModel: LeaderboardViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .padding(top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Leaderboard", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Review the ladder, scan the standouts, and sort by what matters most.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LeaderboardSortMode.entries.forEach { mode ->
                    AssistChip(
                        onClick = { viewModel.updateSortMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    LeaderboardSortMode.TOP_RATED -> "Top rated"
                                    LeaderboardSortMode.MOST_PLAYED -> "Most played"
                                    LeaderboardSortMode.MOST_SKIPPED -> "Most skipped"
                                }
                            )
                        },
                        colors = if (uiState.sortMode == mode) {
                            androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                labelColor = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            androidx.compose.material3.AssistChipDefaults.assistChipColors()
                        }
                    )
                }
            }
        }

        if (uiState.songs.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No songs ranked yet", style = MaterialTheme.typography.titleLarge)
                        Text("Add a few tracks or import a playlist to generate your first standings.")
                    }
                }
            }
        } else {
            itemsIndexed(uiState.songs, key = { _, song -> song.id }) { index, song ->
                LeaderboardRow(index = index, song = song)
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LeaderboardRow(index: Int, song: Song) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SongArtwork(
                artworkUrl = song.artworkUrl,
                modifier = Modifier.size(68.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "#${index + 1} ${song.title}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LeaderboardStatChip("Rating ${song.rating}")
                    LeaderboardStatChip("${song.wins}W ${song.losses}L")
                    if (song.skips > 0) {
                        LeaderboardStatChip("${song.skips} skips")
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardStatChip(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium
    )
}
