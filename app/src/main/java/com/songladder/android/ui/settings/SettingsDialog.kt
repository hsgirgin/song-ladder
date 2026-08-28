package com.songladder.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.songladder.android.domain.model.formatScoreTenths
import com.songladder.android.ui.library.LibraryViewModel

data class LibrarySettingsActions(
    val onImportJson: () -> Unit = {},
    val onExportJson: () -> Unit = {},
    val onResetLibrary: () -> Unit = {}
)

@Composable
fun SettingsDialog(
    viewModel: SettingsViewModel,
    libraryViewModel: LibraryViewModel,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showImportConfirm by rememberSaveable { mutableStateOf(false) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }

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
        onMetadataRetrievalChanged = viewModel::setMetadataRetrievalEnabled,
        onShowTipsAgain = viewModel::showTipsAgain,
        onHistorySelectionChanged = viewModel::toggleDeletedHistorySelection,
        onClearSelection = viewModel::clearSelection,
        onDeleteSelected = viewModel::deleteSelectedRankingHistory,
        libraryActions = LibrarySettingsActions(
            onImportJson = { showImportConfirm = true },
            onExportJson = { exportLauncher.launch("song-ladder-export.json") },
            onResetLibrary = { showResetConfirm = true }
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
}

@Composable
internal fun SettingsDialogContent(
    uiState: SettingsUiState,
    onDismiss: () -> Unit,
    onAutoPlayChanged: (Boolean) -> Unit,
    onMetadataRetrievalChanged: (Boolean) -> Unit,
    onShowTipsAgain: () -> Unit,
    onHistorySelectionChanged: (String) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
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
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_metadata_retrieval),
                            checked = uiState.settings.metadataRetrievalEnabled,
                            onCheckedChange = onMetadataRetrievalChanged
                        )
                        Text(
                            text = stringResource(R.string.settings_metadata_retrieval_caption),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item {
                    OutlinedButton(onClick = onShowTipsAgain, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_show_tips_again))
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

