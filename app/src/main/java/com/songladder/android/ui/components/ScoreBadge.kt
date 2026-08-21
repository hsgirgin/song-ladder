package com.songladder.android.ui.components

import android.graphics.Color as AndroidColor
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.songladder.android.R
import com.songladder.android.domain.model.formatScoreTenths
import kotlin.math.roundToInt

private val ScoreGradientRed = Color(0xFFE53935)
private val ScoreGradientYellow = Color(0xFFFDD835)
private val ScoreGradientGreen = Color(0xFF43A047)

private val ScoreGradientRedHsv = ScoreGradientRed.toHsv()
private val ScoreGradientYellowHsv = ScoreGradientYellow.toHsv()
private val ScoreGradientGreenHsv = ScoreGradientGreen.toHsv()

/**
 * Continuous red -> yellow -> green interpolation across the 0.0-10.0 score range.
 * Interpolates through HSV (sweeping hue) rather than straight RGB, which avoids the
 * dull, muddy oranges a direct RGB lerp between red and yellow produces partway through.
 * The three anchor scores (0, 5.0, 10.0) return their exact literal colors.
 */
fun scoreGradientColor(scoreTenths: Int): Color {
    val fraction = (scoreTenths / 100f).coerceIn(0f, 1f)
    return when (fraction) {
        0f -> ScoreGradientRed
        0.5f -> ScoreGradientYellow
        1f -> ScoreGradientGreen
        else -> if (fraction < 0.5f) {
            lerpHsv(ScoreGradientRedHsv, ScoreGradientYellowHsv, fraction / 0.5f)
        } else {
            lerpHsv(ScoreGradientYellowHsv, ScoreGradientGreenHsv, (fraction - 0.5f) / 0.5f)
        }
    }
}

private data class Hsv(val hue: Float, val saturation: Float, val value: Float)

private fun Color.toHsv(): Hsv {
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV(
        (red * 255f).roundToInt(),
        (green * 255f).roundToInt(),
        (blue * 255f).roundToInt(),
        hsv
    )
    return Hsv(hsv[0], hsv[1], hsv[2])
}

private fun lerpHsv(start: Hsv, end: Hsv, t: Float): Color {
    val hue = start.hue + (end.hue - start.hue) * t
    val saturation = start.saturation + (end.saturation - start.saturation) * t
    val value = start.value + (end.value - start.value) * t
    return Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))
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
