package com.songladder.android.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.PlaylistImportPreview
import com.songladder.android.ui.components.SongArtwork

private enum class AddSongSection {
    YoutubeMusic,
    Spotify,
    Add
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSongSheet(
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var expandedSection by rememberSaveable { mutableStateOf<AddSongSection?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var artist by rememberSaveable { mutableStateOf("") }
    var album by rememberSaveable { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Add songs", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(com.songladder.android.R.string.add_song_sheet_close)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                searchSection(
                    uiState = uiState,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onAddTrack = viewModel::addSearchResult
                )

                item {
                    ExpandableSectionHeader(
                        label = "Import from YouTube Music",
                        expanded = expandedSection == AddSongSection.YoutubeMusic,
                        onClick = {
                            expandedSection = if (expandedSection == AddSongSection.YoutubeMusic) {
                                null
                            } else {
                                AddSongSection.YoutubeMusic
                            }
                        }
                    )
                }
                if (expandedSection == AddSongSection.YoutubeMusic) {
                    item {
                        YoutubeMusicSectionContent(
                            uiState = uiState,
                            onUrlChange = viewModel::updateYoutubeMusicPlaylistUrl,
                            onPreview = viewModel::previewYoutubeMusicPlaylist,
                            onConfirmImport = viewModel::confirmYoutubeMusicPreviewImport,
                            onClearPreview = viewModel::clearYoutubeMusicPreview
                        )
                    }
                }

                item {
                    ExpandableSectionHeader(
                        label = "Import from Spotify",
                        expanded = expandedSection == AddSongSection.Spotify,
                        onClick = {
                            expandedSection = if (expandedSection == AddSongSection.Spotify) {
                                null
                            } else {
                                AddSongSection.Spotify
                            }
                        }
                    )
                }
                if (expandedSection == AddSongSection.Spotify) {
                    item {
                        SpotifySectionContent(
                            uiState = uiState,
                            onImportFile = { uri -> viewModel.importSpotifyPlaylistFile(context.contentResolver, uri) },
                            onConfirmImport = viewModel::confirmSpotifyPreviewImport,
                            onClearPreview = viewModel::clearSpotifyPreview
                        )
                    }
                }

                item {
                    ExpandableSectionHeader(
                        label = "Add manually",
                        expanded = expandedSection == AddSongSection.Add,
                        onClick = {
                            expandedSection = if (expandedSection == AddSongSection.Add) {
                                null
                            } else {
                                AddSongSection.Add
                            }
                        }
                    )
                }
                if (expandedSection == AddSongSection.Add) {
                    item {
                        AddSongSectionContent(
                            title = title,
                            artist = artist,
                            album = album,
                            onTitleChange = { title = it },
                            onArtistChange = { artist = it },
                            onAlbumChange = { album = it },
                            onAddSong = {
                                viewModel.addSong(title, artist, album)
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ExpandableSectionHeader(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null
            )
        }
    }
}

private fun LazyListScope.searchSection(
    uiState: LibraryUiState,
    onSearchQueryChange: (String) -> Unit,
    onAddTrack: (MusicTrackCandidate) -> Unit
) {
    item {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Search songs", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = { Text("Song or artist") },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = stringResource(com.songladder.android.R.string.add_song_search_icon)
                        )
                    },
                    trailingIcon = {
                        if (uiState.isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(
                                    Icons.Rounded.Clear,
                                    contentDescription = stringResource(com.songladder.android.R.string.add_song_search_clear)
                                )
                            }
                        }
                    },
                    supportingText = {
                        Text(
                            if (uiState.isSearching) "Searching automatically..." else "Search starts automatically as you type."
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (uiState.searchResults.isEmpty()) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Ready to search", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Type a song, artist, or album and results will appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    } else {
        items(uiState.searchResults, key = { it.externalId }) { track ->
            ItunesSearchResultRow(
                track = track,
                isAdding = track.externalId in uiState.addingTrackIds,
                isAdded = track.externalId in uiState.addedTrackIds,
                isDuplicate = track.externalId in uiState.duplicateTrackIds,
                onAdd = { onAddTrack(track) }
            )
        }
    }
}

@Composable
internal fun AddSongSectionContent(
    title: String,
    artist: String,
    album: String,
    modifier: Modifier = Modifier,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onAlbumChange: (String) -> Unit,
    onAddSong: () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = title, onValueChange = onTitleChange, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = artist, onValueChange = onArtistChange, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = album, onValueChange = onAlbumChange, label = { Text("Album") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = onAddSong,
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add to ladder")
            }
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SongArtwork(
                artworkUrl = track.artworkUrl,
                modifier = Modifier.size(56.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (track.album.isNotBlank()) {
                    Text(
                        text = track.album,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            OutlinedButton(
                onClick = onAdd,
                enabled = !isAdding && !isAdded
            ) {
                if (isAdding) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        when {
                            isAdded -> "Added"
                            isDuplicate -> "Added"
                            else -> "Add"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun YoutubeMusicSectionContent(
    uiState: LibraryUiState,
    onUrlChange: (String) -> Unit,
    onPreview: () -> Unit,
    onConfirmImport: () -> Unit,
    onClearPreview: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = uiState.youtubeMusicPlaylistUrl,
                    onValueChange = onUrlChange,
                    label = { Text("Playlist URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onPreview,
                        enabled = !uiState.isPreviewLoading && !uiState.isImportingPreview,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (uiState.isPreviewLoading) "Previewing..." else "Preview playlist")
                    }
                    if (uiState.youtubeMusicPreview != null || uiState.previewError != null) {
                        TextButton(onClick = onClearPreview) {
                            Text("Clear")
                        }
                    }
                }
                if (!uiState.previewError.isNullOrBlank()) {
                    Text(uiState.previewError, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        uiState.youtubeMusicPreview?.let { preview ->
            PlaylistPreviewCard(
                preview = preview,
                isImporting = uiState.isImportingPreview,
                onConfirmImport = onConfirmImport
            )
        }
    }
}

@Composable
private fun SpotifySectionContent(
    uiState: LibraryUiState,
    onImportFile: (Uri) -> Unit,
    onConfirmImport: () -> Unit,
    onClearPreview: () -> Unit
) {
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportFile)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Import a playlist you've exported to JSON with an external tool.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { filePickerLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    enabled = !uiState.isPreviewLoading && !uiState.isImportingPreview,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose playlist JSON file")
                }
            }
        }

        if (!uiState.previewError.isNullOrBlank() || uiState.spotifyPreview != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!uiState.previewError.isNullOrBlank()) {
                    Text(
                        uiState.previewError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                }
                TextButton(onClick = onClearPreview) {
                    Text("Clear")
                }
            }
        }

        uiState.spotifyPreview?.let { preview ->
            PlaylistPreviewCard(
                preview = preview,
                isImporting = uiState.isImportingPreview,
                onConfirmImport = onConfirmImport
            )
        }
    }
}

@Composable
private fun PlaylistPreviewCard(
    preview: PlaylistImportPreview,
    isImporting: Boolean,
    onConfirmImport: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(preview.playlistTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${preview.importableTracks.size} ready to import",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (preview.ambiguousTracks.isNotEmpty()) {
                Text(
                    "${preview.ambiguousTracks.size} need review and will be skipped",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                preview.ambiguousTracks.take(3).forEach { track ->
                    Text(
                        "• ${track.rawTitle.ifBlank { "Unknown title" }} — ${track.rawArtist.ifBlank { track.reason }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (preview.unsupportedCount > 0) {
                Text(
                    "${preview.unsupportedCount} unsupported items were ignored",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onConfirmImport,
                enabled = preview.importableTracks.isNotEmpty() && !isImporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isImporting) "Importing..." else "Import ready tracks")
            }
        }
    }
}
