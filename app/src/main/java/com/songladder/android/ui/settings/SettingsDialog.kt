package com.songladder.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.songladder.android.R
import com.songladder.android.domain.model.DeletedRankingHistory
import com.songladder.android.domain.model.PlaylistImportPreview
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.formatScoreTenths
import com.songladder.android.ui.components.SongArtwork
import com.songladder.android.ui.library.LibraryViewModel

data class LibrarySettingsState(
    val songs: List<Song> = emptyList(),
    val youtubeMusicPlaylistUrl: String = "",
    val isPreviewLoading: Boolean = false,
    val youtubeMusicPreview: PlaylistImportPreview? = null,
    val previewError: String? = null,
    val isImportingPreview: Boolean = false
)

data class LibrarySettingsActions(
    val onYoutubeMusicPlaylistUrlChange: (String) -> Unit = {},
    val onPreviewYoutubeMusicPlaylist: () -> Unit = {},
    val onConfirmYoutubeMusicPreview: () -> Unit = {},
    val onClearYoutubeMusicPreview: () -> Unit = {},
    val onImportJson: () -> Unit = {},
    val onExportJson: () -> Unit = {},
    val onResetLibrary: () -> Unit = {},
    val onRemoveSong: (String) -> Unit = {}
)

@Composable
fun SettingsDialog(
    viewModel: SettingsViewModel,
    libraryViewModel: LibraryViewModel,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showImportConfirm by rememberSaveable { mutableStateOf(false) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    var songPendingRemoval by rememberSaveable { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { libraryViewModel.importJson(contentResolver = context.contentResolver, uri = it) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { libraryViewModel.exportJson(contentResolver = context.contentResolver, uri = it) }
    }

    SettingsDialogContent(
        uiState = uiState,
        onDismiss = onDismiss,
        onAutoPlayChanged = viewModel::setAutoPlayMatchupPreviews,
        onShowTipsAgain = viewModel::showTipsAgain,
        onHistorySelectionChanged = viewModel::toggleDeletedHistorySelection,
        onClearSelection = viewModel::clearSelection,
        onDeleteSelected = viewModel::deleteSelectedRankingHistory,
        libraryState = LibrarySettingsState(
            songs = libraryUiState.songs,
            youtubeMusicPlaylistUrl = libraryUiState.youtubeMusicPlaylistUrl,
            isPreviewLoading = libraryUiState.isPreviewLoading,
            youtubeMusicPreview = libraryUiState.youtubeMusicPreview,
            previewError = libraryUiState.previewError,
            isImportingPreview = libraryUiState.isImportingPreview
        ),
        libraryActions = LibrarySettingsActions(
            onYoutubeMusicPlaylistUrlChange = libraryViewModel::updateYoutubeMusicPlaylistUrl,
            onPreviewYoutubeMusicPlaylist = libraryViewModel::previewYoutubeMusicPlaylist,
            onConfirmYoutubeMusicPreview = libraryViewModel::confirmYoutubeMusicPreviewImport,
            onClearYoutubeMusicPreview = libraryViewModel::clearYoutubeMusicPreview,
            onImportJson = { showImportConfirm = true },
            onExportJson = { exportLauncher.launch("song-ladder-export.json") },
            onResetLibrary = { showResetConfirm = true },
            onRemoveSong = { songId -> songPendingRemoval = songId }
        )
    )

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
                        libraryViewModel.resetLibrary()
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

    songPendingRemoval?.let { songId ->
        val song = libraryUiState.songs.firstOrNull { it.id == songId }
        AlertDialog(
            onDismissRequest = { songPendingRemoval = null },
            title = { Text("Remove song?") },
            text = { Text("${song?.title ?: "This song"} will be removed from your ladder and rankings.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        songPendingRemoval = null
                        libraryViewModel.removeSong(songId)
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
}

@Composable
internal fun SettingsDialogContent(
    uiState: SettingsUiState,
    onDismiss: () -> Unit,
    onAutoPlayChanged: (Boolean) -> Unit,
    onShowTipsAgain: () -> Unit,
    onHistorySelectionChanged: (String) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    libraryState: LibrarySettingsState = LibrarySettingsState(),
    libraryActions: LibrarySettingsActions = LibrarySettingsActions(),
    modifier: Modifier = Modifier
) {
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    val selectedEventCount = uiState.deletedHistories
        .filter { it.rankingSubjectId in uiState.selectedHistoryIds }
        .sumOf { it.eventCount }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            LazyColumn(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_autoplay_previews),
                        checked = uiState.settings.autoPlayMatchupPreviews,
                        onCheckedChange = onAutoPlayChanged
                    )
                }
                item {
                    OutlinedButton(onClick = onShowTipsAgain, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_show_tips_again))
                    }
                }
                item {
                    Text(
                        text = "Your library",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item {
                    YoutubeMusicImportCard(
                        youtubeMusicPlaylistUrl = libraryState.youtubeMusicPlaylistUrl,
                        isPreviewLoading = libraryState.isPreviewLoading,
                        youtubeMusicPreview = libraryState.youtubeMusicPreview,
                        previewError = libraryState.previewError,
                        isImportingPreview = libraryState.isImportingPreview,
                        onYoutubeMusicPlaylistUrlChange = libraryActions.onYoutubeMusicPlaylistUrlChange,
                        onPreviewYoutubeMusicPlaylist = libraryActions.onPreviewYoutubeMusicPlaylist,
                        onConfirmYoutubeMusicPreview = libraryActions.onConfirmYoutubeMusicPreview,
                        onClearYoutubeMusicPreview = libraryActions.onClearYoutubeMusicPreview
                    )
                }
                if (libraryState.songs.isNotEmpty()) {
                    items(libraryState.songs, key = { it.id }) { song ->
                        LibrarySongRow(song = song, onRemoveSong = { libraryActions.onRemoveSong(song.id) })
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Backup and reset", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = libraryActions.onImportJson, modifier = Modifier.weight(1f)) {
                                    Text("Import JSON")
                                }
                                OutlinedButton(onClick = libraryActions.onExportJson, modifier = Modifier.weight(1f)) {
                                    Text("Export JSON")
                                }
                            }
                            OutlinedButton(onClick = libraryActions.onResetLibrary, modifier = Modifier.fillMaxWidth()) {
                                Text("Reset library")
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.settings_deleted_histories_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (uiState.deletedHistories.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.settings_deleted_histories_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(
                        items = uiState.deletedHistories,
                        key = { it.rankingSubjectId }
                    ) { history ->
                        DeletedHistoryRow(
                            history = history,
                            selected = history.rankingSubjectId in uiState.selectedHistoryIds,
                            onSelectedChange = { onHistorySelectionChanged(history.rankingSubjectId) }
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                        ) {
                            TextButton(
                                onClick = onClearSelection,
                                enabled = uiState.selectedHistoryIds.isNotEmpty() && !uiState.isDeletingHistory
                            ) {
                                Text(stringResource(R.string.settings_clear_selection))
                            }
                            Button(
                                onClick = { showDeleteConfirm = true },
                                enabled = uiState.selectedHistoryIds.isNotEmpty() && !uiState.isDeletingHistory
                            ) {
                                Text(
                                    pluralStringResource(
                                        R.plurals.settings_delete_history_action,
                                        uiState.selectedHistoryIds.size,
                                        uiState.selectedHistoryIds.size
                                    )
                                )
                            }
                        }
                    }
                }
                item {
                    SettingsStatusText(status = uiState.status)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_done))
            }
        }
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.settings_confirm_delete_history_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.settings_confirm_delete_history_message,
                        selectedEventCount,
                        selectedEventCount
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteSelected()
                    }
                ) {
                    Text(stringResource(R.string.settings_confirm_delete_history_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.rating_editor_cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DeletedHistoryRow(
    history: DeletedRankingHistory,
    selected: Boolean,
    onSelectedChange: () -> Unit,
    modifier: Modifier = Modifier
) {
    val unknownTitle = stringResource(R.string.settings_unknown_title)
    val unknownArtist = stringResource(R.string.settings_unknown_artist)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = { onSelectedChange() })
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = history.title.ifBlank { unknownTitle },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = history.artist.ifBlank { unknownArtist },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val score = history.scoreTenths?.let { formatScoreTenths(it) }
                ?: stringResource(R.string.score_unrated)
            Text(
                text = pluralStringResource(
                    R.plurals.settings_deleted_history_summary,
                    history.eventCount,
                    score,
                    history.eventCount
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsStatusText(status: SettingsStatus) {
    val text = when (status) {
        SettingsStatus.None -> return
        SettingsStatus.SaveFailed -> stringResource(R.string.settings_save_failed)
        SettingsStatus.DeleteFailed -> stringResource(R.string.settings_delete_failed)
        is SettingsStatus.DeletedHistory -> pluralStringResource(
            R.plurals.settings_deleted_history_success,
            status.eventCount,
            status.eventCount
        )
    }
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium
    )
}

@Composable
private fun YoutubeMusicImportCard(
    youtubeMusicPlaylistUrl: String,
    isPreviewLoading: Boolean,
    youtubeMusicPreview: PlaylistImportPreview?,
    previewError: String?,
    isImportingPreview: Boolean,
    onYoutubeMusicPlaylistUrlChange: (String) -> Unit,
    onPreviewYoutubeMusicPlaylist: () -> Unit,
    onConfirmYoutubeMusicPreview: () -> Unit,
    onClearYoutubeMusicPreview: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Experimental playlist import", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                    ?: stringResource(R.string.score_unrated)
                Text(
                    "${stringResource(R.string.score_value, score)} - ${song.wins}W ${song.losses}L"
                )
            }
            OutlinedButton(onClick = onRemoveSong) {
                Text("Remove")
            }
        }
    }
}
