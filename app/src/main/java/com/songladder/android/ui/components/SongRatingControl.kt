package com.songladder.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.songladder.android.R
import com.songladder.android.domain.model.MAX_SCORE_TENTHS
import com.songladder.android.domain.model.MIN_SCORE_TENTHS
import com.songladder.android.domain.model.formatScoreTenths

@Composable
fun SongRatingControl(
    scoreTenths: Int,
    onScoreChange: (Int) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scoreText = formatScoreTenths(scoreTenths)
    val incrementLabel = stringResource(R.string.rating_editor_increment)
    val decrementLabel = stringResource(R.string.rating_editor_decrement)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.rating_editor_value, scoreText),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Slider(
            value = scoreTenths.toFloat(),
            onValueChange = { value ->
                onScoreChange(value.toInt().coerceIn(MIN_SCORE_TENTHS, MAX_SCORE_TENTHS))
            },
            valueRange = MIN_SCORE_TENTHS.toFloat()..MAX_SCORE_TENTHS.toFloat(),
            steps = MAX_SCORE_TENTHS - MIN_SCORE_TENTHS - 1,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(
                            label = incrementLabel
                        ) {
                            onScoreChange((scoreTenths + 1).coerceAtMost(MAX_SCORE_TENTHS))
                            true
                        },
                        CustomAccessibilityAction(
                            label = decrementLabel
                        ) {
                            onScoreChange((scoreTenths - 1).coerceAtLeast(MIN_SCORE_TENTHS))
                            true
                        }
                    )
                }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.rating_editor_min_label), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.rating_editor_max_label), style = MaterialTheme.typography.labelSmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
        ) {
            TextButton(onClick = onCancel, enabled = enabled) {
                Text(stringResource(R.string.rating_editor_cancel))
            }
            Button(onClick = onSave, enabled = enabled) {
                Text(stringResource(R.string.rating_editor_save, scoreText))
            }
        }
    }
}
