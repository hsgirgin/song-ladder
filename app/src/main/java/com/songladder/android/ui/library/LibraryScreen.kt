package com.songladder.android.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.ui.components.SongArtwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importJson(contentResolver = context.contentResolver, uri = it) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportJson(contentResolver = context.contentResolver, uri = it) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Library", style = MaterialTheme.typography.headlineLarge)
            Text("Build your pool from scratch, starter packs, or iTunes search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Add a song", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Artist") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text("Album") }, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            viewModel.addSong(title, artist, album)
                            title = ""
                            artist = ""
                            album = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add to ladder")
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Search songs", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Use iTunes search to find tracks with artwork and add them straight into your ladder.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = viewModel::updateSearchQuery,
                        label = { Text("Song or artist") },
                        supportingText = {
                            Text(
                                if (uiState.isSearching) "Searching automatically..." else "Search starts automatically as you type."
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (uiState.searchResults.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.searchResults.forEach { track ->
                                ItunesSearchResultRow(
                                    track = track,
                                    isAdding = track.externalId in uiState.addingTrackIds,
                                    isAdded = track.externalId in uiState.addedTrackIds,
                                    isDuplicate = track.externalId in uiState.duplicateTrackIds,
                                    onAdd = { viewModel.addSearchResult(track) }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = viewModel::seedSampleSongs, modifier = Modifier.weight(1f)) {
                    Text("Load sample pack")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }, modifier = Modifier.weight(1f)) {
                    Text("Import JSON")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("song-ladder-export.json") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export JSON")
                }
                OutlinedButton(onClick = viewModel::resetLibrary, modifier = Modifier.weight(1f)) {
                    Text("Reset library")
                }
            }
        }

        item {
            if (uiState.statusMessage.isNotBlank()) {
                Text(uiState.statusMessage, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }

        item {
            Text("Current pool", style = MaterialTheme.typography.titleLarge)
        }

        items(uiState.songs, key = { it.id }) { song ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleLarge)
                    Text("${song.artist} - ${song.album.ifBlank { "Single" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Rating ${song.rating} - ${song.wins}W ${song.losses}L - ${song.sourceType.name.lowercase()}")
                    OutlinedButton(onClick = { viewModel.removeSong(song.id) }) {
                        Text("Remove")
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ItunesSearchResultRow(
    track: MusicTrackCandidate,
    isAdding: Boolean,
    isAdded: Boolean,
    isDuplicate: Boolean,
    onAdd: () -> Unit
) {
    val highlighted = isAdded || isDuplicate
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isAdded) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else if (isDuplicate) {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = if (highlighted) {
            BorderStroke(
                width = 1.dp,
                color = if (isAdded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SongArtwork(
                artworkUrl = track.artworkUrl,
                modifier = Modifier.size(72.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleLarge)
                Text("${track.artist} - ${track.album}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isAdded) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("Added to ladder") },
                        leadingIcon = {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        },
                        shape = RoundedCornerShape(999.dp)
                    )
                } else if (isDuplicate) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("Already in ladder") },
                        shape = RoundedCornerShape(999.dp)
                    )
                }
            }
            OutlinedButton(
                onClick = onAdd,
                enabled = !isAdding && !isAdded
            ) {
                Text(
                    when {
                        isAdding -> "Adding..."
                        isAdded -> "Added"
                        isDuplicate -> "Added"
                        else -> "Add"
                    }
                )
            }
        }
    }
}
