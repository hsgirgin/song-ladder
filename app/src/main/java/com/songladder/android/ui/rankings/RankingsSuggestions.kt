package com.songladder.android.ui.rankings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.songladder.android.R
import com.songladder.android.ui.components.ScoreTransitionBadges
import com.songladder.android.ui.components.SongArtwork

@Composable
internal fun SuggestionsSection(
    rows: List<SuggestionRow>,
    callbacks: SuggestionCallbacks,
    modifier: Modifier = Modifier
) {
    var showAcceptConfirm by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.rankings_suggestions_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            rows.forEach { row ->
                SuggestionRowCard(
                    row = row,
                    selected = row.suggestion.subjectId in callbacks.selectedIds,
                    enabled = !callbacks.isSaving,
                    onSelectedChange = { callbacks.onToggleSelection(row.suggestion.subjectId) },
                    onAccept = { callbacks.onAccept(row.suggestion.subjectId, row.suggestion.suggestedScoreTenths) },
                    onEdit = { callbacks.onEdit(row) },
                    onDismissLater = { callbacks.onDismissLater(row.suggestion.subjectId) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                TextButton(
                    onClick = callbacks.onClearSelection,
                    enabled = callbacks.selectedIds.isNotEmpty() && !callbacks.isSaving
                ) {
                    Text(stringResource(R.string.settings_clear_selection))
                }
                Button(
                    onClick = { showAcceptConfirm = true },
                    enabled = callbacks.selectedIds.isNotEmpty() && !callbacks.isSaving
                ) {
                    Text(
                        pluralStringResource(
                            R.plurals.rankings_accept_selected_suggestions_action,
                            callbacks.selectedIds.size,
                            callbacks.selectedIds.size
                        )
                    )
                }
            }
        }
    }

    if (showAcceptConfirm) {
        AlertDialog(
            onDismissRequest = { showAcceptConfirm = false },
            title = { Text(stringResource(R.string.rankings_confirm_accept_suggestions_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.rankings_confirm_accept_suggestions_message,
                        callbacks.selectedIds.size,
                        callbacks.selectedIds.size
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAcceptConfirm = false
                        callbacks.onAcceptSelected()
                    }
                ) {
                    Text(stringResource(R.string.rankings_confirm_accept_suggestions_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAcceptConfirm = false }) {
                    Text(stringResource(R.string.rating_editor_cancel))
                }
            }
        )
    }
}

@Composable
private fun SuggestionRowCard(
    row: SuggestionRow,
    selected: Boolean,
    enabled: Boolean,
    onSelectedChange: () -> Unit,
    onAccept: () -> Unit,
    onEdit: () -> Unit,
    onDismissLater: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = selected, onCheckedChange = { onSelectedChange() }, enabled = enabled)
            SongArtwork(artworkUrl = row.song.artworkUrl, modifier = Modifier.size(40.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = row.song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.rankings_suggestion_comparison_count,
                        row.suggestion.comparisonCount,
                        row.suggestion.comparisonCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ScoreTransitionBadges(
                oldScoreTenths = row.song.scoreTenths,
                newScoreTenths = row.suggestion.suggestedScoreTenths,
                oldScoreSize = 28.dp,
                newScoreSize = 36.dp
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
        ) {
            TextButton(onClick = onEdit, enabled = enabled) {
                Text(stringResource(R.string.rankings_suggestion_edit))
            }
            TextButton(onClick = onDismissLater, enabled = enabled) {
                Text(stringResource(R.string.rank_skip_for_now))
            }
            Button(onClick = onAccept, enabled = enabled) {
                Text(stringResource(R.string.rankings_suggestion_accept))
            }
        }
    }
}
