package com.songladder.android.ui.rankings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.songladder.android.R
import com.songladder.android.domain.model.RankedAlbum
import com.songladder.android.ui.components.SongArtwork

/**
 * One row per NEEDS_REVIEW album, shown above the Albums list/grid exactly where
 * [SuggestionsSection] sits above the Songs tab's unrated list. "Choose" opens
 * [AlbumDetailDialog] with its candidate picker already populated, rather than
 * duplicating a second candidate-choosing UI here.
 */
@Composable
internal fun AlbumMatchReviewSection(
    albums: List<RankedAlbum>,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (albums.isEmpty()) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.rankings_album_review_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            albums.forEach { rankedAlbum ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SongArtwork(artworkUrl = rankedAlbum.album.artworkUrl, modifier = Modifier.size(48.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = rankedAlbum.album.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = rankedAlbum.album.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(onClick = { onChoose(rankedAlbum.album.id) }) {
                        Text(stringResource(R.string.rankings_album_choose_action))
                    }
                }
            }
        }
    }
}
