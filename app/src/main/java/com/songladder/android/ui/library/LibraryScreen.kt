package com.songladder.android.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.PlaylistImportPreview
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.TombstoneImportAction
import com.songladder.android.domain.model.TombstoneImportResolution
import com.songladder.android.domain.model.formatScoreTenths
import com.songladder.android.ui.components.SongArtwork

private enum class LibraryTab(val label: String) {
    Search("Search"),
    Add("Add"),
    Manage("Manage")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.Search) }
    var title by rememberSaveable { mutableStateOf("") }
    var artist by rememberSaveable { mutableStateOf("") }
    var album by rememberSaveable { mutableStateOf("") }
    var showImportConfirm by rememberSaveable { mutableStateOf(false) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    var songPendingRemoval by remember { mutableStateOf<Song?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importJson(contentResolver = context.contentResolver, uri = it) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportJson(contentResolver = context.contentResolver, uri = it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Library", style = MaterialTheme.typography.headlineMedium)
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(com.songladder.android.R.string.settings_title)
                        )
                    }
                }
                Text(
                    "Search first, add fast, and manage the ladder without digging through one long screen.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LibraryReadinessBanner(songCount = uiState.songs.size)

        val jsonImportMessage = uiState.jsonImportRepairedCount?.let { repairedCount ->
            pluralStringResource(
                com.songladder.android.R.plurals.library_json_import_repaired_count,
                repairedCount,
                repairedCount
            )
        }
        val statusMessage = uiState.statusMessage.takeIf { it.isNotBlank() && !uiState.isSearchMessage() }
            ?: jsonImportMessage
        if (!statusMessage.isNullOrBlank()) {
            LibraryStatusBanner(message = statusMessage)
        }

        TabRow(selectedTabIndex = selectedTab.ordinal) {
            LibraryTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label) }
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .imePadding()
        ) {
            when (selectedTab) {
                LibraryTab.Search -> SearchTabContent(
                    uiState = uiState,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onAddTrack = viewModel::addSearchResult
                )

                LibraryTab.Add -> AddTabContent(
                    title = title,
                    artist = artist,
                    album = album,
                    onTitleChange = { title = it },
                    onArtistChange = { artist = it },
                    onAlbumChange = { album = it },
                    onAddSong = {
                        viewModel.addSong(title, artist, album)
                    },
                    onLoadSamplePack = viewModel::seedSampleSongs
                )

                LibraryTab.Manage -> ManageTabContent(
                    songs = uiState.songs,
                    youtubeMusicPlaylistUrl = uiState.youtubeMusicPlaylistUrl,
                    isPreviewLoading = uiState.isPreviewLoading,
                    youtubeMusicPreview = uiState.youtubeMusicPreview,
                    previewError = uiState.previewError,
                    isImportingPreview = uiState.isImportingPreview,
                    onYoutubeMusicPlaylistUrlChange = viewModel::updateYoutubeMusicPlaylistUrl,
                    onPreviewYoutubeMusicPlaylist = viewModel::previewYoutubeMusicPlaylist,
                    onConfirmYoutubeMusicPreview = viewModel::confirmYoutubeMusicPreviewImport,
                    onClearYoutubeMusicPreview = viewModel::clearYoutubeMusicPreview,
                    onImportJson = { showImportConfirm = true },
                    onExportJson = { exportLauncher.launch("song-ladder-export.json") },
                    onResetLibrary = { showResetConfirm = true },
                    onRemoveSong = { songId ->
                        songPendingRemoval = uiState.songs.firstOrNull { it.id == songId }
                    }
                )
            }
        }
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text("Replace library?") },
            text = { Text("Importing JSON replaces the current library and ranking history with the selected file.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        importLauncher.launch(arrayOf("application/json"))
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset library?") },
            text = { Text("This removes every song and clears ranking progress.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        viewModel.resetLibrary()
                    }
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    songPendingRemoval?.let { song ->
        AlertDialog(
            onDismissRequest = { songPendingRemoval = null },
            title = { Text("Remove song?") },
            text = { Text("${song.title} will be removed from your ladder and rankings.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        songPendingRemoval = null
                        viewModel.removeSong(song.id)
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { songPendingRemoval = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    uiState.tombstoneConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = viewModel::cancelTombstoneConflict,
            title = { Text(stringResource(com.songladder.android.R.string.library_restore_history_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(com.songladder.android.R.string.library_restore_history_message))
                    Text("${conflict.candidate.title} — ${conflict.candidate.artist}")
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    conflict.matches.forEach { match ->
                        TextButton(
                            onClick = {
                                viewModel.resolveTombstoneConflict(
                                    TombstoneImportResolution(
                                        action = TombstoneImportAction.RESTORE,
                                        rankingSubjectId = match.rankingSubjectId
                                    )
                                )
                            }
                        ) {
                            Text(
                                stringResource(
                                    com.songladder.android.R.string.library_restore_history_action,
                                    match.title,
                                    match.artist
                                )
                            )
                        }
                        TextButton(
                            onClick = {
                                viewModel.resolveTombstoneConflict(
                                    TombstoneImportResolution(
                                        action = TombstoneImportAction.START_FRESH,
                                        rankingSubjectId = match.rankingSubjectId
                                    )
                                )
                            }
                        ) {
                            Text(stringResource(com.songladder.android.R.string.library_start_fresh_action))
                        }
                    }
                    TextButton(onClick = viewModel::cancelTombstoneConflict) {
                        Text(stringResource(com.songladder.android.R.string.library_cancel_import_action))
                    }
                }
            }
        )
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
                    supportingText = {
                        Text(
                            if (uiState.isSearching) "Searching automatically..." else "Search starts automatically as you type."
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                val searchStatusMessage = uiState.statusMessage.takeIf { uiState.isSearchMessage() }
                if (!searchStatusMessage.isNullOrBlank()) {
                    Text(
                        text = searchStatusMessage,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
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
    onAddSong: () -> Unit,
    onLoadSamplePack: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Quick start your ladder", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Add one manually or drop in a sample pack so you can get to ranking fast.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(value = title, onValueChange = onTitleChange, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = artist, onValueChange = onArtistChange, label = { Text("Artist") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = album, onValueChange = onAlbumChange, label = { Text("Album") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = onAddSong, modifier = Modifier.fillMaxWidth()) {
                        Text("Add to ladder")
                    }
                    OutlinedButton(onClick = onLoadSamplePack, modifier = Modifier.fillMaxWidth()) {
                        Text("Load sample pack")
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun ManageTabContent(
    songs: List<Song>,
    youtubeMusicPlaylistUrl: String,
    isPreviewLoading: Boolean,
    youtubeMusicPreview: PlaylistImportPreview?,
    previewError: String?,
    isImportingPreview: Boolean,
    onYoutubeMusicPlaylistUrlChange: (String) -> Unit,
    onPreviewYoutubeMusicPlaylist: () -> Unit,
    onConfirmYoutubeMusicPreview: () -> Unit,
    onClearYoutubeMusicPreview: () -> Unit,
    onImportJson: () -> Unit,
    onExportJson: () -> Unit,
    onResetLibrary: () -> Unit,
    onRemoveSong: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Experimental playlist import", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Paste a public YouTube Music playlist link to preview tracks before import.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = youtubeMusicPlaylistUrl,
                        onValueChange = onYoutubeMusicPlaylistUrlChange,
                        label = { Text("YouTube Music playlist URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onPreviewYoutubeMusicPlaylist,
                            enabled = !isPreviewLoading && !isImportingPreview,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isPreviewLoading) "Previewing..." else "Preview playlist")
                        }
                        if (youtubeMusicPreview != null || previewError != null) {
                            TextButton(onClick = onClearYoutubeMusicPreview) {
                                Text("Clear")
                            }
                        }
                    }
                    if (!previewError.isNullOrBlank()) {
                        Text(previewError, color = MaterialTheme.colorScheme.error)
                    }
                    youtubeMusicPreview?.let { preview ->
                        YoutubeMusicPreviewCard(
                            preview = preview,
                            isImporting = isImportingPreview,
                            onConfirmImport = onConfirmYoutubeMusicPreview
                        )
                    }
                }
            }
        }

        item {
            Text("Current pool", style = MaterialTheme.typography.titleLarge)
        }

        if (songs.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No songs yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Search for songs or add one manually to start your ladder.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(songs, key = { it.id }) { song ->
                LibrarySongRow(song = song, onRemoveSong = { onRemoveSong(song.id) })
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Manage library", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Backup, restore, or reset after you have the current pool where you want it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onImportJson, modifier = Modifier.weight(1f)) {
                            Text("Import JSON")
                        }
                        OutlinedButton(onClick = onExportJson, modifier = Modifier.weight(1f)) {
                            Text("Export JSON")
                        }
                    }
                    OutlinedButton(onClick = onResetLibrary, modifier = Modifier.fillMaxWidth()) {
                        Text("Reset library")
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
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

@Composable
private fun LibrarySongRow(song: Song, onRemoveSong: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SongArtwork(
                artworkUrl = song.artworkUrl,
                modifier = Modifier.size(64.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${song.artist} - ${song.album.ifBlank { "Single" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val score = song.scoreTenths
                    ?.let { formatScoreTenths(it) }
                    ?: stringResource(com.songladder.android.R.string.score_unrated)
                Text(
                    "${stringResource(com.songladder.android.R.string.score_value, score)} - ${song.wins}W ${song.losses}L"
                )
            }
            OutlinedButton(onClick = onRemoveSong) {
                Text("Remove")
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

private fun LibraryUiState.isSearchMessage(): Boolean {
    return isSearching ||
        statusMessage.startsWith("Found ") ||
        statusMessage.startsWith("No songs found") ||
        statusMessage.startsWith("Keep typing") ||
        statusMessage.startsWith("Searching iTunes") ||
        statusMessage.startsWith("iTunes search failed")
}

@Composable
private fun LibraryReadinessBanner(songCount: Int) {
    val ready = songCount >= 2
    val message = if (ready) {
        "Ready to rank. $songCount songs in your ladder."
    } else {
        "Add ${2 - songCount} more song${if (songCount == 1) "" else "s"} to start ranking."
    }

    Surface(
        color = if (ready) {
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            color = if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}
