package com.songladder.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import com.songladder.android.domain.model.MAX_SCORE_TENTHS
import com.songladder.android.domain.model.MIN_SCORE_TENTHS
import com.songladder.android.domain.model.formatScoreTenths
import kotlin.math.abs

private val ScoreGradientRed = Color(0xFFE53935)
private val ScoreGradientYellow = Color(0xFFFDD835)
private val ScoreGradientGreen = Color(0xFF43A047)

private val ScoreGradientRedHsv = ScoreGradientRed.toHsv()
private val ScoreGradientYellowHsv = ScoreGradientYellow.toHsv()
private val ScoreGradientGreenHsv = ScoreGradientGreen.toHsv()

/**
 * Continuous red -> yellow -> green interpolation across the valid 1.0-10.0 score range.
 * Interpolates through HSV (sweeping hue) rather than straight RGB, which avoids the
 * dull, muddy oranges a direct RGB lerp between red and yellow produces partway through.
 * The three anchor scores (MIN_SCORE_TENTHS, midpoint, MAX_SCORE_TENTHS) return their
 * exact literal colors.
 */
fun scoreGradientColor(scoreTenths: Int): Color {
    val fraction = ((scoreTenths - MIN_SCORE_TENTHS).toFloat() / (MAX_SCORE_TENTHS - MIN_SCORE_TENTHS))
        .coerceIn(0f, 1f)
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

/**
 * Plain-Kotlin HSV conversion, deliberately not android.graphics.Color:
 * that class's methods are unmocked stubs under local JVM unit tests (only
 * instrumented tests get a real Android runtime), so calling them here would
 * crash ScoreBadgeTest with an ExceptionInInitializerError.
 */
private data class Hsv(val hue: Float, val saturation: Float, val value: Float)

private fun Color.toHsv(): Hsv {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    val value = max
    val saturation = if (max == 0f) 0f else delta / max
    val hue = when {
        delta == 0f -> 0f
        max == red -> 60f * (((green - blue) / delta).mod(6f))
        max == green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }
    return Hsv(hue, saturation, value)
}

private fun lerpHsv(start: Hsv, end: Hsv, t: Float): Color {
    val hue = start.hue + (end.hue - start.hue) * t
    val saturation = start.saturation + (end.saturation - start.saturation) * t
    val value = start.value + (end.value - start.value) * t
    val c = value * saturation
    val hPrime = hue / 60f
    val x = c * (1f - abs(hPrime.mod(2f) - 1f))
    val (r1, g1, b1) = when {
        hPrime < 1f -> Triple(c, x, 0f)
        hPrime < 2f -> Triple(x, c, 0f)
        hPrime < 3f -> Triple(0f, c, x)
        hPrime < 4f -> Triple(0f, x, c)
        hPrime < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = value - c
    return Color(red = r1 + m, green = g1 + m, blue = b1 + m)
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

/**
 * Old score -> arrow -> new score, as shown on a suggestion row/card. Renders only the
 * new badge when there is no prior score to transition from.
 */
@Composable
fun ScoreTransitionBadges(
    oldScoreTenths: Int?,
    newScoreTenths: Int,
    modifier: Modifier = Modifier,
    oldScoreSize: Dp = 28.dp,
    newScoreSize: Dp = 48.dp
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (oldScoreTenths != null) {
            ScoreBadge(scoreTenths = oldScoreTenths, size = oldScoreSize)
            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ScoreBadge(scoreTenths = newScoreTenths, size = newScoreSize)
    }
}
