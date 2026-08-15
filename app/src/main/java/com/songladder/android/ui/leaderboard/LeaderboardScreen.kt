package com.songladder.android.ui.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songladder.android.R
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
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(stringResource(R.string.nav_leaderboard), style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.leaderboard_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            LeaderboardSortControls(
                selectedMode = uiState.sortMode,
                onModeSelected = viewModel::updateSortMode
            )
        }

        if (uiState.songs.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.leaderboard_empty_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.leaderboard_empty_message))
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
@OptIn(ExperimentalLayoutApi::class)
internal fun LeaderboardSortControls(
    selectedMode: LeaderboardSortMode,
    onModeSelected: (LeaderboardSortMode) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LeaderboardSortMode.entries.forEach { mode ->
            FilterChip(
                selected = selectedMode == mode,
                onClick = { onModeSelected(mode) },
                label = {
                    Text(
                        when (mode) {
                            LeaderboardSortMode.TOP_RATED -> stringResource(R.string.leaderboard_top_rated)
                            LeaderboardSortMode.MOST_PLAYED -> stringResource(R.string.leaderboard_most_played)
                            LeaderboardSortMode.MOST_SKIPPED -> stringResource(R.string.leaderboard_most_skipped)
                        }
                    )
                }
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun LeaderboardRow(index: Int, song: Song, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LeaderboardStatChip(stringResource(R.string.leaderboard_rating, song.rating))
                    LeaderboardStatChip(
                        stringResource(
                            R.string.leaderboard_match_context,
                            song.matchCount,
                            stringResource(song.confidenceLabel)
                        )
                    )
                    LeaderboardStatChip(
                        stringResource(R.string.leaderboard_wins_losses, song.wins, song.losses)
                    )
                    if (song.skips > 0) {
                        LeaderboardStatChip(pluralStringResource(R.plurals.leaderboard_skips, song.skips, song.skips))
                    }
                }
            }
        }
    }
}

private val Song.matchCount: Int
    get() = wins + losses

private val Song.confidenceLabel: Int
    get() = when (matchCount) {
        0 -> R.string.rank_confidence_new
        1, 2 -> R.string.rank_confidence_early
        else -> R.string.rank_confidence_established
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
