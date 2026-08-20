package com.songladder.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.songladder.android.R
import com.songladder.android.domain.model.formatScoreTenths

private val ScoreGradientRed = Color(0xFFE53935)
private val ScoreGradientYellow = Color(0xFFFDD835)
private val ScoreGradientGreen = Color(0xFF43A047)

/** Continuous red -> yellow -> green interpolation across the 0.0-10.0 score range. */
fun scoreGradientColor(scoreTenths: Int): Color {
    val fraction = (scoreTenths / 100f).coerceIn(0f, 1f)
    return if (fraction <= 0.5f) {
        lerp(ScoreGradientRed, ScoreGradientYellow, fraction / 0.5f)
    } else {
        lerp(ScoreGradientYellow, ScoreGradientGreen, (fraction - 0.5f) / 0.5f)
    }
}

/**
 * Circular score display: the formatted score centered inside a ring whose color
 * grades from red to green across the 0-10 range. Null renders a neutral, unrated
 * state at the same size so layouts don't jump when a score is added.
 */
@Composable
fun ScoreBadge(
    scoreTenths: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val ringColor = scoreTenths?.let(::scoreGradientColor) ?: MaterialTheme.colorScheme.outlineVariant
    val text = scoreTenths?.let { formatScoreTenths(it) } ?: stringResource(R.string.rankings_rate_song_short)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(BorderStroke(2.5.dp, ringColor), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
