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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songladder.android.domain.model.MusicTrackCandidate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var spotifyTokenDraft by remember(uiState.spotifyToken) { mutableStateOf(uiState.spotifyToken) }
    val selectedSpotifyTracks = remember { mutableStateListOf<String>() }

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
            Text("Build your pool from scratch, starter packs, or Spotify.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("Spotify import", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Paste a Spotify Web API bearer token, search tracks, then import selected results into your local ladder.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = spotifyTokenDraft,
                        onValueChange = { spotifyTokenDraft = it },
                        label = { Text("Spotify token") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = uiState.spotifyQuery,
                            onValueChange = viewModel::updateSpotifyQuery,
                            label = { Text("Search tracks") },
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            viewModel.saveSpotifyToken(spotifyTokenDraft)
                            viewModel.searchSpotify(spotifyTokenDraft)
                        }) {
                            Text("Search")
                        }
                    }
                    if (uiState.spotifyResults.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.spotifyResults.forEach { track ->
                                SpotifySearchRow(
                                    track = track,
                                    selected = track.externalId in selectedSpotifyTracks,
                                    onToggle = {
                                        if (track.externalId in selectedSpotifyTracks) {
                                            selectedSpotifyTracks.remove(track.externalId)
                                        } else {
                                            selectedSpotifyTracks.add(track.externalId)
                                        }
                                    }
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    val selection = uiState.spotifyResults.filter { it.externalId in selectedSpotifyTracks }
                                    viewModel.importSpotifySelection(selection)
                                    selectedSpotifyTracks.clear()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Import selected tracks")
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
private fun SpotifySearchRow(
    track: MusicTrackCandidate,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleLarge)
                Text("${track.artist} - ${track.album}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onToggle) {
                Text(if (selected) "Selected" else "Select")
            }
        }
    }
}
