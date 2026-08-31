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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.Suggestion
import com.songladder.android.ui.components.ScoreTransitionBadges
import com.songladder.android.ui.components.SongArtwork
import com.songladder.android.ui.components.SongRatingControl
import kotlin.math.abs

internal enum class CardReaction {
    Idle,
    Winner,
    Loser,
    Skip
}

private const val SWIPE_SELECTION_THRESHOLD = 0.08f

@Composable
fun RankScreen(
    viewModel: RankViewModel,
    onOpenSettings: () -> Unit = {},
    onAddSongs: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val matchup = uiState.matchup
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

    val pendingSuggestion = uiState.pendingSuggestion
    val suggestionSong = pendingSuggestion?.let { suggestion ->
        uiState.songs.firstOrNull { it.rankingSubjectId == suggestion.subjectId }
    }

    if (pendingSuggestion != null && suggestionSong != null) {
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
            CompactSuggestionCard(
                suggestion = pendingSuggestion,
                song = suggestionSong,
                onAccept = { scoreTenths -> viewModel.acceptPendingSuggestion(scoreTenths) },
                onLater = viewModel::dismissPendingSuggestionLater,
                isSaving = uiState.isSavingSuggestion
            )
        }
    } else if (uiState.isReady && matchup != null && !uiState.caughtUp) {
        RankMatchupContent(
            uiState = uiState,
            matchup = matchup,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            onChoose = viewModel::rankWinner,
            onPreview = viewModel::togglePreview
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
            } else {
                EmptyRankState(onAddSongs = onAddSongs)
            }
        }
    }
}

@Composable
internal fun CompactSuggestionCard(
    suggestion: Suggestion,
    song: Song,
    onAccept: (Int) -> Unit,
    onLater: () -> Unit,
    isSaving: Boolean = false
) {
    var editing by rememberSaveable(suggestion.subjectId) { mutableStateOf(false) }
    var draftScore by rememberSaveable(suggestion.subjectId) { mutableIntStateOf(suggestion.suggestedScoreTenths) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(com.songladder.android.R.string.rankings_suggestions_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SongArtwork(artworkUrl = song.artworkUrl, modifier = Modifier.size(56.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ScoreTransitionBadges(
                    oldScoreTenths = song.scoreTenths,
                    newScoreTenths = if (editing) draftScore else suggestion.suggestedScoreTenths
                )
            }
            if (editing) {
                SongRatingControl(
                    scoreTenths = draftScore,
                    onScoreChange = { draftScore = it },
                    onSave = { onAccept(draftScore) },
                    onCancel = {
                        draftScore = suggestion.suggestedScoreTenths
                        editing = false
                    },
                    enabled = !isSaving
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onLater, enabled = !isSaving) {
                        Text(stringResource(com.songladder.android.R.string.rank_skip_for_now))
                    }
                    TextButton(onClick = { editing = true }, enabled = !isSaving) {
                        Text(stringResource(com.songladder.android.R.string.rankings_suggestion_edit))
                    }
                    Button(onClick = { onAccept(suggestion.suggestedScoreTenths) }, enabled = !isSaving) {
                        Text(stringResource(com.songladder.android.R.string.rankings_suggestion_accept))
                    }
                }
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
    onChoose: (String, String) -> Unit,
    onPreview: (String) -> Unit = {}
) {
    BoxWithConstraints(modifier = modifier) {
        val compact = maxHeight < 520.dp || LocalDensity.current.fontScale > 1.3f
        val dragThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
        var dragOffset by remember(matchup.left.id, matchup.right.id) { mutableFloatStateOf(0f) }
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
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var lastPosition = down.position
                        var dragIntentEstablished = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.firstOrNull { it.id == down.id }
                                ?.let { change ->
                                    lastPosition = change.position
                                    dragOffset = (lastPosition.y - down.position.y)
                                        .coerceIn(-dragThresholdPx * 1.35f, dragThresholdPx * 1.35f)
                                    if (!dragIntentEstablished &&
                                        abs(lastPosition.y - down.position.y) > touchSlop
                                    ) {
                                        dragIntentEstablished = true
                                    }
                                    if (dragIntentEstablished) {
                                        change.consume()
                                    }
                                }
                        } while (event.changes.any { it.pressed })

                        val dragY = lastPosition.y - down.position.y
                        when {
                            dragY <= -dragThresholdPx -> {
                                dragOffset = 0f
                                onChoose(matchup.right.id, matchup.left.id)
                            }
                            dragY >= dragThresholdPx -> {
                                dragOffset = 0f
                                onChoose(matchup.left.id, matchup.right.id)
                            }
                            else -> dragOffset = 0f
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
                    compact = compact,
                    isTopCard = true,
                    reaction = uiState.visualFeedback.reactionFor(matchup.left.id),
                    previewState = uiState.previews[matchup.left.id],
                    swipeProgress = (dragOffset / dragThresholdPx).coerceIn(-1.35f, 1.35f),
                    onPreview = { onPreview(matchup.left.id) }
                )
                MinimalSongChoiceCard(
                    modifier = if (compact) Modifier.heightIn(min = 152.dp) else Modifier.weight(1f),
                    song = matchup.right,
                    compact = compact,
                    isTopCard = false,
                    reaction = uiState.visualFeedback.reactionFor(matchup.right.id),
                    previewState = uiState.previews[matchup.right.id],
                    swipeProgress = (dragOffset / dragThresholdPx).coerceIn(-1.35f, 1.35f),
                    onPreview = { onPreview(matchup.right.id) }
                )
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
    compact: Boolean = false,
    isTopCard: Boolean = true,
    reaction: CardReaction,
    previewState: SongPreviewState? = null,
    swipeProgress: Float = 0f,
    onPreview: () -> Unit = {}
) {
    val dragStrength = kotlin.math.abs(swipeProgress).coerceIn(0f, 1f)
    val selectingThisCard = if (isTopCard) {
        swipeProgress > SWIPE_SELECTION_THRESHOLD
    } else {
        swipeProgress < -SWIPE_SELECTION_THRESHOLD
    }
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
    val swipeBorderColor by animateColorAsState(
        targetValue = if (selectingThisCard) {
            MaterialTheme.colorScheme.primary
        } else {
            borderColor
        },
        animationSpec = tween(100),
        label = "rankSwipeBorder"
    )
    val swipeContainerColor by animateColorAsState(
        targetValue = if (selectingThisCard) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            containerColor
        },
        animationSpec = tween(100),
        label = "rankSwipeContainer"
    )
    val swipeScale by animateFloatAsState(
        targetValue = when {
            selectingThisCard -> 1f + (dragStrength * 0.08f)
            dragStrength > SWIPE_SELECTION_THRESHOLD -> 1f - (dragStrength * 0.05f)
            else -> 1f
        },
        animationSpec = tween(100),
        label = "rankSwipeScale"
    )
    val swipeAlpha by animateFloatAsState(
        targetValue = when {
            selectingThisCard -> 1f
            dragStrength > SWIPE_SELECTION_THRESHOLD -> 1f - (dragStrength * 0.38f)
            else -> 1f
        },
        animationSpec = tween(100),
        label = "rankSwipeAlpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (selectingThisCard) 1f else 0f)
            .scale(scale * swipeScale)
            .alpha(alpha * swipeAlpha)
            .graphicsLayer {
                translationY = swipeProgress * if (selectingThisCard) 56.dp.toPx() else -20.dp.toPx()
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = swipeContainerColor),
        onClick = onPreview
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(2.dp, swipeBorderColor, RoundedCornerShape(20.dp))
        ) {
            SongArtwork(
                artworkUrl = song.artworkUrl,
                modifier = Modifier.fillMaxSize()
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = if (compact) 12.dp else 16.dp)) {
                    Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(
                imageVector = when (previewState) {
                    SongPreviewState.Playing -> Icons.Rounded.Pause
                    SongPreviewState.Unavailable -> Icons.Rounded.Refresh
                    else -> Icons.Rounded.PlayArrow
                },
                contentDescription = stringResource(
                    when (previewState) {
                        SongPreviewState.Playing -> com.songladder.android.R.string.rank_pause_preview
                        SongPreviewState.Unavailable -> com.songladder.android.R.string.rank_preview_retry
                        else -> com.songladder.android.R.string.rank_preview_action
                    }
                ),
                tint = if (previewState == SongPreviewState.Unavailable) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            )
            if (selectingThisCard) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = stringResource(com.songladder.android.R.string.rank_swipe_selecting),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyRankState(onAddSongs: () -> Unit) {
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
            Button(onClick = onAddSongs) {
                Text("Add songs")
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
