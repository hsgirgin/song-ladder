package com.songladder.android.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.songladder.android.ui.components.SongArtwork

private enum class LibraryTab(val labelRes: Int) {
    Search(com.songladder.android.R.string.library_tab_search),
    Add(com.songladder.android.R.string.library_tab_add),
    Manage(com.songladder.android.R.string.library_tab_manage)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel) {
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
                Text(stringResource(com.songladder.android.R.string.nav_library), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(com.songladder.android.R.string.library_intro),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LibraryReadinessBanner(songCount = uiState.songs.size)

        val statusMessage = uiState.statusMessage?.localizedText()
        if (!statusMessage.isNullOrBlank() && !uiState.isSearchMessage()) {
            LibraryStatusBanner(message = statusMessage)
        }

        TabRow(selectedTabIndex = selectedTab.ordinal) {
            LibraryTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(stringResource(tab.labelRes)) }
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
            title = { Text(stringResource(com.songladder.android.R.string.library_replace_title)) },
            text = { Text(stringResource(com.songladder.android.R.string.library_replace_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        importLauncher.launch(arrayOf("application/json"))
                    }
                ) {
                    Text(stringResource(com.songladder.android.R.string.action_import))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text(stringResource(com.songladder.android.R.string.action_cancel))
                }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(com.songladder.android.R.string.library_reset_title)) },
            text = { Text(stringResource(com.songladder.android.R.string.library_reset_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        viewModel.resetLibrary()
                    }
                ) {
                    Text(stringResource(com.songladder.android.R.string.action_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(com.songladder.android.R.string.action_cancel))
                }
            }
        )
    }

    songPendingRemoval?.let { song ->
        AlertDialog(
            onDismissRequest = { songPendingRemoval = null },
            title = { Text(stringResource(com.songladder.android.R.string.library_remove_title)) },
            text = { Text(stringResource(com.songladder.android.R.string.library_remove_message, song.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        songPendingRemoval = null
                        viewModel.removeSong(song.id)
                    }
                ) {
                    Text(stringResource(com.songladder.android.R.string.action_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { songPendingRemoval = null }) {
                    Text(stringResource(com.songladder.android.R.string.action_cancel))
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
                Text(stringResource(com.songladder.android.R.string.library_search_title), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = { Text(stringResource(com.songladder.android.R.string.library_search_label)) },
                    supportingText = {
                        Text(
                            stringResource(if (uiState.isSearching) com.songladder.android.R.string.library_searching else com.songladder.android.R.string.library_search_hint)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                val searchStatusMessage = uiState.statusMessage
                    ?.takeIf { uiState.isSearchMessage() }
                    ?.localizedText()
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
                    Text(stringResource(com.songladder.android.R.string.library_ready_to_search), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(com.songladder.android.R.string.library_search_empty),
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
                    Text(stringResource(com.songladder.android.R.string.library_quick_start), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(com.songladder.android.R.string.library_quick_start_message),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(value = title, onValueChange = onTitleChange, label = { Text(stringResource(com.songladder.android.R.string.library_title_label)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = artist, onValueChange = onArtistChange, label = { Text(stringResource(com.songladder.android.R.string.library_artist_label)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = album, onValueChange = onAlbumChange, label = { Text(stringResource(com.songladder.android.R.string.library_album_label)) }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = onAddSong, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(com.songladder.android.R.string.library_add_to_ladder))
                    }
                    OutlinedButton(onClick = onLoadSamplePack, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(com.songladder.android.R.string.library_load_sample_pack))
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
    previewError: LibraryMessage?,
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
                    Text(stringResource(com.songladder.android.R.string.library_playlist_import_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(com.songladder.android.R.string.library_playlist_import_message),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = youtubeMusicPlaylistUrl,
                        onValueChange = onYoutubeMusicPlaylistUrlChange,
                        label = { Text(stringResource(com.songladder.android.R.string.library_playlist_url_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onPreviewYoutubeMusicPlaylist,
                            enabled = !isPreviewLoading && !isImportingPreview,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(if (isPreviewLoading) com.songladder.android.R.string.library_previewing else com.songladder.android.R.string.library_preview_playlist))
                        }
                        if (youtubeMusicPreview != null || previewError != null) {
                            TextButton(onClick = onClearYoutubeMusicPreview) {
                                Text(stringResource(com.songladder.android.R.string.action_clear))
                            }
                        }
                    }
                    previewError?.localizedText()?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
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
            Text(stringResource(com.songladder.android.R.string.library_current_pool), style = MaterialTheme.typography.titleLarge)
        }

        if (songs.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(com.songladder.android.R.string.library_no_songs), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(com.songladder.android.R.string.library_no_songs_message),
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
                    Text(stringResource(com.songladder.android.R.string.library_manage_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(com.songladder.android.R.string.library_manage_message),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onImportJson, modifier = Modifier.weight(1f)) {
                            Text(stringResource(com.songladder.android.R.string.action_import_json))
                        }
                        OutlinedButton(onClick = onExportJson, modifier = Modifier.weight(1f)) {
                            Text(stringResource(com.songladder.android.R.string.action_export_json))
                        }
                    }
                    OutlinedButton(onClick = onResetLibrary, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(com.songladder.android.R.string.library_reset_title).removeSuffix("?"))
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
                stringResource(com.songladder.android.R.string.library_ready_to_import, preview.importableTracks.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (preview.ambiguousTracks.isNotEmpty()) {
                Text(
                    stringResource(com.songladder.android.R.string.library_ambiguous_to_skip, preview.ambiguousTracks.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                preview.ambiguousTracks.take(3).forEach { track ->
                    Text(
                        stringResource(
                            com.songladder.android.R.string.library_ambiguous_track,
                            track.rawTitle.ifBlank {
                                stringResource(com.songladder.android.R.string.library_unknown_title)
                            },
                            track.rawArtist.ifBlank { track.reason }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (preview.unsupportedCount > 0) {
                Text(
                    stringResource(com.songladder.android.R.string.library_unsupported_ignored, preview.unsupportedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onConfirmImport,
                enabled = preview.importableTracks.isNotEmpty() && !isImporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (isImporting) com.songladder.android.R.string.library_importing else com.songladder.android.R.string.library_import_ready_tracks))
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
                Text("${song.artist} - ${song.album.ifBlank { stringResource(com.songladder.android.R.string.library_single) }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(com.songladder.android.R.string.library_rating_stats, song.rating, song.wins, song.losses))
            }
            OutlinedButton(onClick = onRemoveSong) {
                Text(stringResource(com.songladder.android.R.string.action_remove))
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
                        label = { Text(stringResource(com.songladder.android.R.string.library_added_to_ladder)) },
                        leadingIcon = {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        },
                        shape = RoundedCornerShape(999.dp)
                    )
                } else if (isDuplicate) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(com.songladder.android.R.string.library_already_in_ladder)) },
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
                        isAdding -> stringResource(com.songladder.android.R.string.library_adding)
                        isAdded || isDuplicate -> stringResource(com.songladder.android.R.string.library_added)
                        else -> stringResource(com.songladder.android.R.string.library_add)
                    }
                )
            }
        }
    }
}

private fun LibraryUiState.isSearchMessage(): Boolean {
    val messageType = statusMessage?.type
    return isSearching ||
        messageType == LibraryMessageType.KEEP_TYPING ||
        messageType == LibraryMessageType.SEARCHING ||
        messageType == LibraryMessageType.SEARCH_EMPTY ||
        messageType == LibraryMessageType.SEARCH_RESULTS ||
        messageType == LibraryMessageType.SEARCH_FAILED
}

@Composable
private fun LibraryMessage.localizedText(): String =
    stringResource(type.stringRes, *args.toTypedArray())

private val LibraryMessageType.stringRes: Int
    get() = when (this) {
        LibraryMessageType.KEEP_TYPING -> com.songladder.android.R.string.library_status_keep_typing
        LibraryMessageType.SONG_ADDED -> com.songladder.android.R.string.library_status_song_added
        LibraryMessageType.SONG_REMOVED -> com.songladder.android.R.string.library_status_song_removed
        LibraryMessageType.LIBRARY_RESET -> com.songladder.android.R.string.library_status_library_reset
        LibraryMessageType.SAMPLE_IMPORTED -> com.songladder.android.R.string.library_status_sample_imported
        LibraryMessageType.SAMPLE_ALREADY_IMPORTED -> com.songladder.android.R.string.library_status_sample_already_imported
        LibraryMessageType.ADDING_TRACK -> com.songladder.android.R.string.library_status_adding_track
        LibraryMessageType.TRACK_ADDED -> com.songladder.android.R.string.library_status_track_added
        LibraryMessageType.TRACK_DUPLICATE -> com.songladder.android.R.string.library_status_track_duplicate
        LibraryMessageType.JSON_IMPORTED -> com.songladder.android.R.string.library_status_json_imported
        LibraryMessageType.PLAYLIST_IMPORTED -> com.songladder.android.R.string.library_status_playlist_imported
        LibraryMessageType.JSON_EXPORTED -> com.songladder.android.R.string.library_status_json_exported
        LibraryMessageType.PREVIEWED_PLAYLIST -> com.songladder.android.R.string.library_status_previewed_playlist
        LibraryMessageType.SEARCHING -> com.songladder.android.R.string.library_status_searching
        LibraryMessageType.SEARCH_EMPTY -> com.songladder.android.R.string.library_status_search_empty
        LibraryMessageType.SEARCH_RESULTS -> com.songladder.android.R.string.library_status_search_results
        LibraryMessageType.ADD_FAILED -> com.songladder.android.R.string.library_error_add
        LibraryMessageType.REMOVE_FAILED -> com.songladder.android.R.string.library_error_remove
        LibraryMessageType.RESET_FAILED -> com.songladder.android.R.string.library_error_reset
        LibraryMessageType.SAMPLE_IMPORT_FAILED -> com.songladder.android.R.string.library_error_sample_import
        LibraryMessageType.IMPORT_FAILED -> com.songladder.android.R.string.library_error_import
        LibraryMessageType.JSON_IMPORT_FAILED -> com.songladder.android.R.string.library_error_json_import
        LibraryMessageType.EXPORT_FAILED -> com.songladder.android.R.string.library_error_export
        LibraryMessageType.PLAYLIST_URL_REQUIRED -> com.songladder.android.R.string.library_error_playlist_url
        LibraryMessageType.PLAYLIST_PREVIEW_FAILED -> com.songladder.android.R.string.library_error_playlist_preview
        LibraryMessageType.PLAYLIST_IMPORT_FAILED -> com.songladder.android.R.string.library_error_playlist_import
        LibraryMessageType.SEARCH_FAILED -> com.songladder.android.R.string.library_error_search
    }

@Composable
private fun LibraryReadinessBanner(songCount: Int) {
    val ready = songCount >= 2
    val message = if (ready) {
        stringResource(com.songladder.android.R.string.library_ready_now, songCount)
    } else {
        pluralStringResource(com.songladder.android.R.plurals.library_ready_message, 2 - songCount, 2 - songCount)
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
