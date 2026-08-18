package com.songladder.android.ui.rank

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.Song
import com.songladder.android.ui.components.SongRatingControl
import com.songladder.android.ui.components.SongArtwork

internal enum class CardReaction {
    Idle,
    Winner,
    Loser,
    Skip
}

@Composable
fun RankScreen(
    viewModel: RankViewModel,
    onOpenSettings: () -> Unit = {},
    onOpenLibrary: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val matchup = uiState.matchup
    val ratingStep = uiState.ratingStep
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, matchup?.left?.id, matchup?.right?.id) {
        fun updatePrefetchForLifecycle() {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                viewModel.updatePreviewPrefetch(matchup)
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> updatePrefetchForLifecycle()
                Lifecycle.Event.ON_STOP -> {
                    viewModel.disarmAutoplayForBackground()
                    viewModel.clearPreviewPrefetch()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        updatePrefetchForLifecycle()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopPreview()
            viewModel.clearPreviewPrefetch()
        }
    }

    if (uiState.isReady && matchup != null && ratingStep == null && !uiState.caughtUp) {
        RankMatchupContent(
            uiState = uiState,
            matchup = matchup,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            onChoose = viewModel::rankWinner
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            MinimalRankHeader(
                uiState = uiState,
                onUndo = viewModel::undo,
                onOpenSettings = onOpenSettings
            )

            if (uiState.caughtUp) {
                CaughtUpState(onContinueAnyway = viewModel::continueAnyway)
            } else if (ratingStep != null) {
                PostMatchRatingContent(
                    ratingStep = ratingStep,
                    enabled = !uiState.isSavingRating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onScoreChange = viewModel::updateRatingDraft,
                    onSave = viewModel::saveRatingStep,
                    onSkip = viewModel::skipRatingStep
                )
            } else {
                EmptyRankState(onOpenLibrary = onOpenLibrary)
            }
        }
    }
}

@Composable
private fun CaughtUpState(onContinueAnyway: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(com.songladder.android.R.string.rank_caught_up_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(com.songladder.android.R.string.rank_caught_up_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onContinueAnyway) {
                Text(stringResource(com.songladder.android.R.string.rank_continue_anyway))
            }
        }
    }
}

@Composable
internal fun RankMatchupContent(
    uiState: RankUiState,
    matchup: Matchup,
    modifier: Modifier = Modifier,
    onChoose: (String, String) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val compact = maxHeight < 520.dp || LocalDensity.current.fontScale > 1.3f
        val artworkSize = if (compact) 72.dp else 112.dp
        val dragThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
        val chooseTopLabel = stringResource(
            com.songladder.android.R.string.rank_choose_song_action,
            matchup.left.title,
            matchup.left.artist
        )
        val chooseBottomLabel = stringResource(
            com.songladder.android.R.string.rank_choose_song_action,
            matchup.right.title,
            matchup.right.artist
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("rank_matchup_drag_area")
                .pointerInput(matchup.left.id, matchup.right.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var lastPosition = down.position
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.firstOrNull { it.id == down.id }
                                ?.let { lastPosition = it.position }
                        } while (event.changes.any { it.pressed })

                        val dragY = lastPosition.y - down.position.y
                        when {
                            dragY <= -dragThresholdPx -> onChoose(matchup.right.id, matchup.left.id)
                            dragY >= dragThresholdPx -> onChoose(matchup.left.id, matchup.right.id)
                        }
                    }
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(chooseTopLabel) {
                            onChoose(matchup.left.id, matchup.right.id)
                            true
                        },
                        CustomAccessibilityAction(chooseBottomLabel) {
                            onChoose(matchup.right.id, matchup.left.id)
                            true
                        }
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MinimalSongChoiceCard(
                    modifier = if (compact) Modifier.heightIn(min = 152.dp) else Modifier.weight(1f),
                    song = matchup.left,
                    artworkSize = artworkSize,
                    compact = compact,
                    reaction = uiState.visualFeedback.reactionFor(matchup.left.id)
                )
                MinimalSongChoiceCard(
                    modifier = if (compact) Modifier.heightIn(min = 152.dp) else Modifier.weight(1f),
                    song = matchup.right,
                    artworkSize = artworkSize,
                    compact = compact,
                    reaction = uiState.visualFeedback.reactionFor(matchup.right.id)
                )
            }
        }
    }
}

@Composable
private fun PostMatchRatingContent(
    ratingStep: PostMatchRatingStep,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onScoreChange: (Int) -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(
                    when (ratingStep.role) {
                        PostMatchRatingRole.Winner -> com.songladder.android.R.string.rank_rate_winner
                        PostMatchRatingRole.Loser -> com.songladder.android.R.string.rank_rate_loser
                    },
                    ratingStep.song.title
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    com.songladder.android.R.string.rank_rating_step_progress,
                    ratingStep.index,
                    ratingStep.total
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = ratingStep.song.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SongRatingControl(
                scoreTenths = ratingStep.draftScoreTenths,
                onScoreChange = onScoreChange,
                onSave = onSave,
                onCancel = { onScoreChange(ratingStep.song.scoreTenths ?: 55) },
                enabled = enabled
            )
            TextButton(
                onClick = onSkip,
                enabled = enabled,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(com.songladder.android.R.string.rank_skip_for_now))
            }
        }
    }
}

@Composable
internal fun MinimalRankHeader(
    uiState: RankUiState,
    onUndo: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val summaryParts = buildList {
        add(
            pluralStringResource(
                com.songladder.android.R.plurals.rank_song_count,
                uiState.songs.size,
                uiState.songs.size
            )
        )
        add(
            pluralStringResource(
                com.songladder.android.R.plurals.rank_match_count,
                uiState.stats.matchCount,
                uiState.stats.matchCount
            )
        )
        if (uiState.streakCount >= 2) {
            add(
                pluralStringResource(
                    com.songladder.android.R.plurals.rank_streak_count,
                    uiState.streakCount,
                    uiState.streakCount
                )
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(com.songladder.android.R.string.destination_matchups), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(com.songladder.android.R.string.settings_title)
                )
            }
        }
        Text(
            text = summaryParts.joinToString(
                separator = stringResource(com.songladder.android.R.string.rank_stat_separator)
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AnimatedVisibility(visible = uiState.message.isNotBlank()) {
            Text(
                text = uiState.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = uiState.undoAvailable) {
            TextButton(onClick = onUndo) {
                Text(stringResource(com.songladder.android.R.string.rank_undo_action))
            }
        }
        AnimatedVisibility(visible = uiState.undoStatus != UndoStatus.None) {
            Text(
                text = stringResource(
                    when (uiState.undoStatus) {
                        UndoStatus.None -> com.songladder.android.R.string.rank_undo_action
                        UndoStatus.Unavailable -> com.songladder.android.R.string.rank_undo_unavailable
                        UndoStatus.Failed -> com.songladder.android.R.string.rank_undo_failed
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun MinimalSongChoiceCard(
    modifier: Modifier = Modifier,
    song: Song,
    artworkSize: androidx.compose.ui.unit.Dp,
    compact: Boolean = false,
    reaction: CardReaction
) {
    val scale by animateFloatAsState(
        targetValue = when (reaction) {
            CardReaction.Winner -> 1.02f
            CardReaction.Loser -> 0.98f
            CardReaction.Skip -> 0.985f
            CardReaction.Idle -> 1f
        },
        animationSpec = tween(durationMillis = 280),
        label = "rankCardScale"
    )
    val alpha by animateFloatAsState(
        targetValue = when (reaction) {
            CardReaction.Winner -> 1f
            CardReaction.Loser -> 0.62f
            CardReaction.Skip -> 0.78f
            CardReaction.Idle -> 1f
        },
        animationSpec = tween(durationMillis = 280),
        label = "rankCardAlpha"
    )
    val borderColor by animateColorAsState(
        targetValue = when (reaction) {
            CardReaction.Winner -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            CardReaction.Loser -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            CardReaction.Skip -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
            CardReaction.Idle -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        },
        animationSpec = tween(durationMillis = 280),
        label = "rankCardBorder"
    )
    val containerColor by animateColorAsState(
        targetValue = when (reaction) {
            CardReaction.Winner -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            CardReaction.Loser -> MaterialTheme.colorScheme.surface
            CardReaction.Skip -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            CardReaction.Idle -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(durationMillis = 280),
        label = "rankCardContainer"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .alpha(alpha),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, borderColor, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SongArtwork(
                artworkUrl = song.artworkUrl,
                modifier = Modifier.size(artworkSize)
            )
            Spacer(modifier = Modifier.size(if (compact) 12.dp else 16.dp))
            Text(
                text = song.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyRankState(onOpenLibrary: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("No matchup yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Button(onClick = onOpenLibrary) {
                Text("Open Library")
            }
        }
    }
}

private fun RankVisualFeedback.reactionFor(songId: String): CardReaction {
    return when (this) {
        RankVisualFeedback.None -> CardReaction.Idle
        is RankVisualFeedback.Choice -> when (songId) {
            winnerId -> CardReaction.Winner
            loserId -> CardReaction.Loser
            else -> CardReaction.Idle
        }

        RankVisualFeedback.Skip -> CardReaction.Skip
    }
}
