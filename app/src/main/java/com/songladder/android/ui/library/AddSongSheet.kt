package com.songladder.android.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.PlaylistImportPreview
import com.songladder.android.ui.components.SongArtwork

private enum class AddSongTab(val label: String) {
    Search("Search"),
    YoutubeMusic("YouTube Music"),
    Add("Add")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSongSheet(
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(AddSongTab.Search) }
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

            val searchStatusMessage = uiState.statusMessage.takeIf { uiState.isSearchMessage() }
            if (!searchStatusMessage.isNullOrBlank()) {
                LibraryStatusBanner(message = searchStatusMessage)
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AddSongTab.entries.forEach { tab ->
                    SegmentedButton(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = tab.ordinal,
                            count = AddSongTab.entries.size
                        )
                    ) {
                        Text(tab.label)
                    }
                }
            }

            Box(modifier = Modifier.heightIn(min = 240.dp)) {
                when (selectedTab) {
                    AddSongTab.Search -> SearchTabContent(
                        uiState = uiState,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onAddTrack = viewModel::addSearchResult
                    )

                    AddSongTab.YoutubeMusic -> YoutubeMusicTabContent(
                        uiState = uiState,
                        onUrlChange = viewModel::updateYoutubeMusicPlaylistUrl,
                        onPreview = viewModel::previewYoutubeMusicPlaylist,
                        onConfirmImport = viewModel::confirmYoutubeMusicPreviewImport,
                        onClearPreview = viewModel::clearYoutubeMusicPreview
                    )

                    AddSongTab.Add -> AddTabContent(
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
        }
    }
}

@Composable
private fun LibraryStatusBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SearchTabContent(
    uiState: LibraryUiState,
    onSearchQueryChange: (String) -> Unit,
    onAddTrack: (MusicTrackCandidate) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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

        if (uiState.searchResults.isEmpty()) {
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
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.searchResults, key = { it.externalId }) { track ->
                    ItunesSearchResultRow(
                        track = track,
                        isAdding = track.externalId in uiState.addingTrackIds,
                        isAdded = track.externalId in uiState.addedTrackIds,
                        isDuplicate = track.externalId in uiState.duplicateTrackIds,
                        onAdd = { onAddTrack(track) }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
internal fun AddTabContent(
    title: String,
    artist: String,
    album: String,
    modifier: Modifier = Modifier,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onAlbumChange: (String) -> Unit,
    onAddSong: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Add manually", style = MaterialTheme.typography.titleLarge)
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
        item { Spacer(modifier = Modifier.height(8.dp)) }
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
private fun YoutubeMusicTabContent(
    uiState: LibraryUiState,
    onUrlChange: (String) -> Unit,
    onPreview: () -> Unit,
    onConfirmImport: () -> Unit,
    onClearPreview: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Import from YouTube Music", style = MaterialTheme.typography.titleLarge)
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
        }
        uiState.youtubeMusicPreview?.let { preview ->
            item {
                YoutubeMusicPreviewCard(
                    preview = preview,
                    isImporting = uiState.isImportingPreview,
                    onConfirmImport = onConfirmImport
                )
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun YoutubeMusicPreviewCard(
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
