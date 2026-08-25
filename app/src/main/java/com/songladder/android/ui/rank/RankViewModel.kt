package com.songladder.android.ui.rank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.MatchupSelection
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.Suggestion
import com.songladder.android.domain.repository.SettingsRepository
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SongPreviewPlayer
import com.songladder.android.domain.repository.SongPreviewResolver
import com.songladder.android.domain.repository.SongRepository
import com.songladder.android.ui.NoOpPreviewPlayer
import com.songladder.android.ui.UnavailablePreviewResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RankVisualFeedback {
    data object None : RankVisualFeedback
    data class Choice(val winnerId: String, val loserId: String) : RankVisualFeedback
    data object Skip : RankVisualFeedback
}

private val RankVisualFeedback.isSettled: Boolean
    get() = this == RankVisualFeedback.None

enum class SongPreviewState {
    Loading,
    Available,
    Playing,
    Unavailable
}

enum class UndoStatus {
    None,
    Unavailable,
    Failed
}

data class RankUiState(
    val songs: List<Song> = emptyList(),
    val stats: AppStats = AppStats(),
    val settings: RankingSettings = RankingSettings(),
    val matchup: Matchup? = null,
    val pendingSuggestion: Suggestion? = null,
    val message: String = "",
    val isReady: Boolean = false,
    val caughtUp: Boolean = false,
    val undoAvailable: Boolean = false,
    val undoStatus: UndoStatus = UndoStatus.None,
    val visualFeedback: RankVisualFeedback = RankVisualFeedback.None,
    val streakCount: Int = 0,
    val autoplayArmed: Boolean = false,
    val previews: Map<String, SongPreviewState> = emptyMap(),
    val isSavingSuggestion: Boolean = false
)

private data class RankSessionState(
    val currentMatchup: Matchup? = null,
    val previousMatchup: Matchup? = null,
    val recentDisplayedMatchups: List<Matchup> = emptyList(),
    val displayedMatchupCount: Int = 0,
    val continueAnyway: Boolean = false,
    val undoAvailable: Boolean = false,
    val undoStatus: UndoStatus = UndoStatus.None,
    val visualFeedback: RankVisualFeedback = RankVisualFeedback.None,
    val streakCount: Int = 0,
    val autoplayArmed: Boolean = false,
    val firstPreviewStartsLeft: Boolean = true,
    val transientMessage: String = "",
    val isSavingSuggestion: Boolean = false
)

private data class PreviewPlaybackSession(
    val queue: List<String>,
    val currentIndex: Int = 0
)

class RankViewModel(
    private val songRepository: SongRepository,
    private val rankingRepository: RankingRepository,
    private val songPreviewResolver: SongPreviewResolver = UnavailablePreviewResolver,
    private val songPreviewPlayer: SongPreviewPlayer = NoOpPreviewPlayer,
    private val settingsRepository: SettingsRepository = DefaultRankSettingsRepository
) : ViewModel() {
    private val matchupEngine = EloMatchupEngine()
    private val sessionState = MutableStateFlow(RankSessionState())
    private val previewStates = MutableStateFlow<Map<String, SongPreviewState>>(emptyMap())
    private val previewUrls = mutableMapOf<String, String>()
    private var clearFeedbackJob: Job? = null
    private var previewPrefetchJob: Job? = null
    private var previewPrefetchGeneration: Long = 0
    private var mutationInFlight = false
    private var previewPlaybackSession: PreviewPlaybackSession? = null

    private val rankingUiState: StateFlow<RankUiState> = combine(
        songRepository.observeSongs(),
        rankingRepository.observeStats(),
        rankingRepository.observeMatchupEvents(),
        settingsRepository.observeSettings(),
        sessionState
    ) { songs, stats, events, settings, session ->
        val selection = ensureMatchupSelected(session, songs, events)
        val matchup = selection.matchup
        RankUiState(
            songs = songs,
            stats = stats,
            settings = settings,
            matchup = matchup,
            message = when {
                session.transientMessage.isNotBlank() -> session.transientMessage
                songs.size < 2 -> "Add at least two songs to start ranking."
                session.streakCount >= 3 -> "Hot streak. Keep the ladder moving."
                else -> ""
            },
            isReady = songs.size >= 2,
            caughtUp = selection.caughtUp,
            undoAvailable = session.undoAvailable,
            undoStatus = session.undoStatus,
            visualFeedback = session.visualFeedback,
            streakCount = session.streakCount,
            autoplayArmed = session.autoplayArmed,
            isSavingSuggestion = session.isSavingSuggestion
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankUiState())

    val uiState: StateFlow<RankUiState> = combine(
        rankingUiState,
        previewStates,
        rankingRepository.observeSuggestions()
    ) { state, previews, suggestions ->
        // Suppress the suggestion during the win/loss flash (matches state.visualFeedback)
        // so a suggestion becoming ready mid-animation can't preempt it, and null out
        // matchup while a suggestion is pending so prefetch doesn't run for a hidden matchup -
        // mirrors the old pendingRatingSteps gating this replaced.
        val resolvedSuggestion = if (state.visualFeedback.isSettled) {
            suggestions.firstOrNull { suggestion ->
                state.songs.any { it.rankingSubjectId == suggestion.subjectId }
            }
        } else {
            null
        }
        state.copy(
            previews = previews,
            pendingSuggestion = resolvedSuggestion,
            matchup = if (resolvedSuggestion != null) null else state.matchup
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankUiState())

    init {
        viewModelScope.launch {
            songPreviewPlayer.events.collect { event ->
                val shouldAdvance = previewPlaybackSession
                    ?.let { session -> session.queue.getOrNull(session.currentIndex) == event.songId }
                    ?: false
                previewStates.update { states ->
                    states + (event.songId to if (event.failed) SongPreviewState.Unavailable else SongPreviewState.Available)
                }
                if (shouldAdvance) {
                    playNextPreviewInSession()
                }
            }
        }
    }

    fun updatePreviewPrefetch(matchup: Matchup?) {
        if (matchup != null) {
            prefetchPreviews(matchup)
        } else {
            clearPreviewPrefetch()
        }
    }

    /**
     * Selects the matchup to show, locking a freshly-chosen one into [sessionState]
     * synchronously (in this same call) rather than waiting on a later, UI-driven side
     * effect. [EloMatchupEngine.selectMatchup] breaks ties randomly, and this combine's
     * transform can re-run several times in quick succession off a single burst of
     * upstream emissions (e.g. the separate Room flow updates from one accept-suggestion
     * DB transaction). Locking in-line closes that window: once a matchup is picked, every
     * subsequent re-run in the same burst sees a non-null [RankSessionState.currentMatchup]
     * and reuses it instead of re-rolling a different one - which previously caused the
     * displayed matchup to flicker to a different pair right after it.
     */
    private fun ensureMatchupSelected(
        session: RankSessionState,
        songs: List<Song>,
        events: List<MatchupEvent>
    ): MatchupSelection {
        if (!session.visualFeedback.isSettled) {
            return MatchupSelection(session.previousMatchup)
        }
        val songsById = songs.associateBy { it.id }
        fun isValid(matchup: Matchup?): Boolean {
            if (matchup == null) return false
            val left = songsById[matchup.left.id] ?: return false
            val right = songsById[matchup.right.id] ?: return false
            // A song can only move from unrated to rated, never back, so if either cached
            // side was unrated when this matchup was chosen but has since been scored (e.g.
            // the user just accepted a suggestion for it while the matchup was hidden behind
            // that suggestion card), the pairing's rated/unrated shape is stale: it may no
            // longer satisfy the active selection tier, and the stale snapshot would still
            // render as unrated. Force a fresh pick instead of showing it.
            val leftNewlyRated = matchup.left.scoreTenths == null && left.scoreTenths != null
            val rightNewlyRated = matchup.right.scoreTenths == null && right.scoreTenths != null
            return !leftNewlyRated && !rightNewlyRated
        }
        if (isValid(session.currentMatchup)) {
            return MatchupSelection(session.currentMatchup)
        }
        val selection = matchupEngine.selectMatchup(
            songs = songs,
            events = events,
            displayedMatchups = session.recentDisplayedMatchups,
            displayedMatchupCount = session.displayedMatchupCount,
            previousMatchup = session.previousMatchup,
            continueAnyway = session.continueAnyway
        )
        val matchup = selection.matchup
        if (matchup != null) {
            sessionState.update { current ->
                if (isValid(current.currentMatchup) || !current.visualFeedback.isSettled) {
                    return@update current
                }
                current.copy(
                    currentMatchup = matchup,
                    recentDisplayedMatchups = (current.recentDisplayedMatchups + matchup)
                        .takeLast(EloMatchupEngine.MAX_BLOCK_WINDOW),
                    displayedMatchupCount = current.displayedMatchupCount + 1,
                    firstPreviewStartsLeft = current.displayedMatchupCount % 2 == 0
                )
            }
        }
        return selection
    }

    private fun prefetchPreviews(matchup: Matchup) {
        val generation = ++previewPrefetchGeneration
        previewPrefetchJob?.cancel()
        previewPlaybackSession = null
        val songs = listOf(matchup.left, matchup.right)
        previewStates.value = songs.associate { it.id to SongPreviewState.Loading }
        previewPrefetchJob = viewModelScope.launch {
            songs.forEach { song ->
                launch prefetchSong@{
                    val url = songPreviewResolver.resolve(song)
                    if (generation != previewPrefetchGeneration) return@prefetchSong
                    if (url != null) {
                        previewUrls[song.id] = url
                    } else {
                        previewUrls.remove(song.id)
                    }
                    previewStates.update { states ->
                        if (generation != previewPrefetchGeneration) return@update states
                        states + (song.id to if (url == null) SongPreviewState.Unavailable else SongPreviewState.Available)
                    }
                    maybeStartArmedAutoplay()
                }
            }
        }
    }

    fun clearPreviewPrefetch() {
        previewPrefetchGeneration += 1
        previewPrefetchJob?.cancel()
        previewPrefetchJob = null
        previewPlaybackSession = null
        previewStates.value = emptyMap()
    }

    fun rankWinner(winnerId: String, loserId: String) {
        if (mutationInFlight) return
        if (uiState.value.visualFeedback != RankVisualFeedback.None) return
        if (uiState.value.pendingSuggestion != null) return
        mutationInFlight = true
        stopPreview()
        viewModelScope.launch {
            try {
                val currentMatchup = uiState.value.matchup ?: return@launch
                rankingRepository.recordBattle(winnerId, loserId)
                    .onSuccess {
                        sessionState.update {
                            it.copy(
                                currentMatchup = null,
                                previousMatchup = currentMatchup,
                                continueAnyway = false,
                                undoAvailable = true,
                                undoStatus = UndoStatus.None,
                                visualFeedback = RankVisualFeedback.Choice(winnerId, loserId),
                                streakCount = it.streakCount + 1,
                                transientMessage = ""
                            )
                        }
                        scheduleFeedbackClear()
                    }
                    .onFailure {
                        sessionState.update { state ->
                            state.copy(transientMessage = "Could not save ranking. Try again.")
                        }
                        scheduleFeedbackClear(delayMillis = 2_500)
                    }
            } finally {
                mutationInFlight = false
            }
        }
    }

    fun skip() {
        if (mutationInFlight) return
        if (uiState.value.visualFeedback != RankVisualFeedback.None) return
        if (uiState.value.pendingSuggestion != null) return
        mutationInFlight = true
        stopPreview()
        viewModelScope.launch {
            try {
                val currentMatchup = uiState.value.matchup ?: return@launch
                rankingRepository.recordSkip(listOf(currentMatchup.left.id, currentMatchup.right.id))
                    .onSuccess {
                        sessionState.update {
                            it.copy(
                                currentMatchup = null,
                                previousMatchup = currentMatchup,
                                continueAnyway = false,
                                undoStatus = UndoStatus.None,
                                visualFeedback = RankVisualFeedback.Skip,
                                streakCount = 0,
                                transientMessage = ""
                            )
                        }
                        scheduleFeedbackClear()
                    }
                    .onFailure {
                        sessionState.update { state ->
                            state.copy(transientMessage = "Could not save skip. Try again.")
                        }
                        scheduleFeedbackClear(delayMillis = 2_500)
                    }
            } finally {
                mutationInFlight = false
            }
        }
    }

    fun continueAnyway() {
        sessionState.update { it.copy(continueAnyway = true, transientMessage = "") }
    }

    fun acceptPendingSuggestion(scoreTenths: Int) {
        runSuggestionMutation(failureMessage = "Could not save score. Try again.") { suggestion ->
            rankingRepository.acceptSuggestion(suggestion.subjectId, scoreTenths)
        }
    }

    fun dismissPendingSuggestionLater() {
        runSuggestionMutation(failureMessage = "Could not dismiss suggestion. Try again.") { suggestion ->
            rankingRepository.dismissSuggestionLater(
                subjectId = suggestion.subjectId,
                suggestedScoreTenths = suggestion.suggestedScoreTenths,
                lastEventSequenceId = suggestion.lastEventSequenceId
            )
        }
    }

    /**
     * Shared by [acceptPendingSuggestion] and [dismissPendingSuggestionLater]. On success,
     * waits (scoped to this one mutation, not a permanent subscription) for [uiState] to
     * reflect the suggestion clearing before attempting autoplay - this avoids reacting to
     * a suggestion being resolved from elsewhere (e.g. the Rankings screen), which would
     * start audio playback while the Rank screen isn't visible.
     */
    private fun runSuggestionMutation(failureMessage: String, action: suspend (Suggestion) -> Result<*>) {
        if (mutationInFlight) return
        val suggestion = uiState.value.pendingSuggestion ?: return
        mutationInFlight = true
        sessionState.update { it.copy(isSavingSuggestion = true) }
        viewModelScope.launch {
            try {
                action(suggestion)
                    .onSuccess {
                        // Bounded: the repository always dismisses the just-resolved
                        // suggestion, so this should clear almost immediately. The
                        // timeout is only a safety net against a future regression of
                        // that invariant re-flagging the same subject and hanging this
                        // wait forever - see the "re-flagging" fix in this file's history.
                        withTimeoutOrNull(2_000) {
                            uiState.first { it.pendingSuggestion?.subjectId != suggestion.subjectId }
                        }
                        maybeStartArmedAutoplay()
                    }
                    .onFailure {
                        sessionState.update { state ->
                            state.copy(transientMessage = failureMessage)
                        }
                        scheduleFeedbackClear(delayMillis = 2_500)
                    }
            } finally {
                mutationInFlight = false
                sessionState.update { it.copy(isSavingSuggestion = false) }
            }
        }
    }

    fun undo() {
        if (mutationInFlight) return
        mutationInFlight = true
        stopPreview()
        viewModelScope.launch {
            try {
                rankingRepository.undoLastWinner()
                    .onSuccess { undone ->
                        sessionState.update {
                            it.copy(
                                previousMatchup = null,
                                continueAnyway = false,
                                undoAvailable = false,
                                undoStatus = if (undone) UndoStatus.None else UndoStatus.Unavailable,
                                visualFeedback = RankVisualFeedback.None,
                                streakCount = if (undone) maxOf(0, it.streakCount - 1) else it.streakCount,
                                transientMessage = ""
                            )
                        }
                        if (!undone) scheduleFeedbackClear(delayMillis = 2_500)
                    }
                    .onFailure {
                        sessionState.update { state ->
                            state.copy(undoStatus = UndoStatus.Failed)
                        }
                        scheduleFeedbackClear(delayMillis = 2_500)
                    }
            } finally {
                mutationInFlight = false
            }
        }
    }

    fun togglePreview(songId: String) {
        when (previewStates.value[songId]) {
            SongPreviewState.Playing -> {
                previewPlaybackSession = null
                songPreviewPlayer.pause()
                previewStates.update { it + (songId to SongPreviewState.Available) }
            }
            SongPreviewState.Available -> {
                sessionState.update { it.copy(autoplayArmed = true) }
                startPreviewSequence(startSongId = songId)
            }
            else -> Unit
        }
    }

    fun stopPreview() {
        previewPlaybackSession = null
        songPreviewPlayer.stop()
        previewStates.update { states ->
            states.mapValues { (_, state) ->
                if (state == SongPreviewState.Playing) SongPreviewState.Available else state
            }
        }
    }

    fun disarmAutoplayForBackground() {
        sessionState.update { it.copy(autoplayArmed = false) }
        stopPreview()
    }

    private fun maybeStartArmedAutoplay() {
        if (!sessionState.value.autoplayArmed || !uiState.value.settings.autoPlayMatchupPreviews) return
        if (uiState.value.pendingSuggestion != null) return
        val matchup = uiState.value.matchup ?: return
        val states = previewStates.value
        val matchupSongIds = setOf(matchup.left.id, matchup.right.id)
        if (matchupSongIds.any { states[it] == SongPreviewState.Loading }) return
        if (states.any { it.key in matchupSongIds && it.value == SongPreviewState.Playing }) return
        startPreviewSequence()
    }

    private fun startPreviewSequence(startSongId: String? = null) {
        val matchup = uiState.value.matchup ?: return
        val orderedIds = when {
            startSongId != null -> listOf(startSongId) + listOf(matchup.left.id, matchup.right.id).filterNot { it == startSongId }
            sessionState.value.firstPreviewStartsLeft -> listOf(matchup.left.id, matchup.right.id)
            else -> listOf(matchup.right.id, matchup.left.id)
        }.filter { songId ->
            previewUrls.containsKey(songId) && previewStates.value[songId] != SongPreviewState.Unavailable
        }
        if (orderedIds.isEmpty()) return
        previewPlaybackSession = PreviewPlaybackSession(queue = orderedIds)
        playPreviewAtCurrentSessionIndex()
    }

    private fun playNextPreviewInSession() {
        val session = previewPlaybackSession ?: return
        val nextSession = session.copy(currentIndex = session.currentIndex + 1)
        if (nextSession.currentIndex >= nextSession.queue.size) {
            previewPlaybackSession = null
            return
        }
        previewPlaybackSession = nextSession
        playPreviewAtCurrentSessionIndex()
    }

    private fun playPreviewAtCurrentSessionIndex() {
        val session = previewPlaybackSession ?: return
        val songId = session.queue.getOrNull(session.currentIndex) ?: return
        val url = previewUrls[songId] ?: return
        runCatching { songPreviewPlayer.play(songId, url) }
            .onSuccess {
                previewStates.update { states ->
                    states.mapValues { (id, state) ->
                        when {
                            id == songId -> SongPreviewState.Playing
                            state == SongPreviewState.Playing -> SongPreviewState.Available
                            else -> state
                        }
                    }
                }
            }
            .onFailure {
                previewPlaybackSession = null
                previewStates.update { it + (songId to SongPreviewState.Available) }
            }
    }

    private fun scheduleFeedbackClear(delayMillis: Long = 325) {
        clearFeedbackJob?.cancel()
        clearFeedbackJob = viewModelScope.launch {
            delay(delayMillis)
            sessionState.update { it.copy(visualFeedback = RankVisualFeedback.None, transientMessage = "") }
        }
    }
}

private data object DefaultRankSettingsRepository : SettingsRepository {
    override fun observeSettings() = kotlinx.coroutines.flow.flowOf(RankingSettings())
    override suspend fun saveSettings(settings: RankingSettings): Result<Unit> = Result.success(Unit)
}
