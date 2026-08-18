package com.songladder.android.ui.rank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.repository.SettingsRepository
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SongPreviewPlayer
import com.songladder.android.domain.repository.SongPreviewResolver
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RankVisualFeedback {
    data object None : RankVisualFeedback
    data class Choice(val winnerId: String, val loserId: String) : RankVisualFeedback
    data object Skip : RankVisualFeedback
}

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

enum class PostMatchRatingRole {
    Winner,
    Loser
}

data class PostMatchRatingStep(
    val song: Song,
    val role: PostMatchRatingRole,
    val index: Int,
    val total: Int,
    val draftScoreTenths: Int
)

data class RankUiState(
    val songs: List<Song> = emptyList(),
    val stats: AppStats = AppStats(),
    val settings: RankingSettings = RankingSettings(),
    val matchup: Matchup? = null,
    val ratingStep: PostMatchRatingStep? = null,
    val message: String = "",
    val isReady: Boolean = false,
    val caughtUp: Boolean = false,
    val undoAvailable: Boolean = false,
    val undoStatus: UndoStatus = UndoStatus.None,
    val visualFeedback: RankVisualFeedback = RankVisualFeedback.None,
    val streakCount: Int = 0,
    val autoplayArmed: Boolean = false,
    val isSavingRating: Boolean = false,
    val previews: Map<String, SongPreviewState> = emptyMap()
)

private data class PendingPostMatchRating(
    val song: Song,
    val role: PostMatchRatingRole
)

private data class RankSessionState(
    val currentMatchup: Matchup? = null,
    val previousMatchup: Matchup? = null,
    val recentDisplayedMatchups: List<Matchup> = emptyList(),
    val displayedMatchupCount: Int = 0,
    val continueAnyway: Boolean = false,
    val pendingRatingSteps: List<PendingPostMatchRating> = emptyList(),
    val ratingDraftScoreTenths: Int = 55,
    val ratingTotalSteps: Int = 0,
    val isSavingRating: Boolean = false,
    val undoAvailable: Boolean = false,
    val undoStatus: UndoStatus = UndoStatus.None,
    val visualFeedback: RankVisualFeedback = RankVisualFeedback.None,
    val streakCount: Int = 0,
    val autoplayArmed: Boolean = false,
    val firstPreviewStartsLeft: Boolean = true,
    val transientMessage: String = ""
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
        val activeSongIds = songs.mapTo(mutableSetOf()) { it.id }
        val currentMatchup = session.currentMatchup
            ?.takeIf { it.left.id in activeSongIds && it.right.id in activeSongIds }
        val selection = if (session.pendingRatingSteps.isNotEmpty()) {
            com.songladder.android.domain.model.MatchupSelection(null)
        } else if (session.visualFeedback == RankVisualFeedback.None) {
            currentMatchup?.let { com.songladder.android.domain.model.MatchupSelection(it) }
                ?: matchupEngine.selectMatchup(
                    songs = songs,
                    events = events,
                    displayedMatchups = session.recentDisplayedMatchups,
                    displayedMatchupCount = session.displayedMatchupCount,
                    previousMatchup = session.previousMatchup,
                    continueAnyway = session.continueAnyway
                )
        } else {
            com.songladder.android.domain.model.MatchupSelection(session.previousMatchup)
        }
        val matchup = selection.matchup
        val ratingStep = session.pendingRatingSteps.firstOrNull()?.let { pending ->
            PostMatchRatingStep(
                song = pending.song,
                role = pending.role,
                index = session.ratingTotalSteps - session.pendingRatingSteps.size + 1,
                total = session.ratingTotalSteps,
                draftScoreTenths = session.ratingDraftScoreTenths
            )
        }
        RankUiState(
            songs = songs,
            stats = stats,
            settings = settings,
            matchup = matchup,
            ratingStep = ratingStep,
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
            isSavingRating = session.isSavingRating
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankUiState())

    val uiState: StateFlow<RankUiState> = combine(rankingUiState, previewStates) { state, previews ->
        state.copy(previews = previews)
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
            markMatchupDisplayed(matchup)
            prefetchPreviews(matchup)
        } else {
            clearPreviewPrefetch()
        }
    }

    private fun markMatchupDisplayed(matchup: Matchup) {
        sessionState.update { state ->
            if (state.currentMatchup.hasSamePairAs(matchup)) {
                state
            } else {
                state.copy(
                    currentMatchup = matchup,
                    recentDisplayedMatchups = (state.recentDisplayedMatchups + matchup).takeLast(3),
                    displayedMatchupCount = state.displayedMatchupCount + 1,
                    firstPreviewStartsLeft = state.displayedMatchupCount % 2 == 0
                )
            }
        }
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
        if (uiState.value.visualFeedback != RankVisualFeedback.None || uiState.value.ratingStep != null) return
        mutationInFlight = true
        stopPreview()
        viewModelScope.launch {
            try {
                val currentMatchup = uiState.value.matchup ?: return@launch
                rankingRepository.recordBattle(winnerId, loserId)
                    .onSuccess {
                        val ratingSteps = currentMatchup.postMatchRatingSteps(winnerId, loserId)
                        sessionState.update {
                            it.copy(
                                currentMatchup = null,
                                previousMatchup = currentMatchup,
                                continueAnyway = false,
                                pendingRatingSteps = ratingSteps,
                                ratingDraftScoreTenths = ratingSteps.firstOrNull()?.song?.scoreTenths ?: 55,
                                ratingTotalSteps = ratingSteps.size,
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
        if (uiState.value.visualFeedback != RankVisualFeedback.None || uiState.value.ratingStep != null) return
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

    fun updateRatingDraft(scoreTenths: Int) {
        sessionState.update { it.copy(ratingDraftScoreTenths = scoreTenths) }
    }

    fun saveRatingStep() {
        if (mutationInFlight) return
        val session = sessionState.value
        val step = session.pendingRatingSteps.firstOrNull() ?: return
        val scoreTenths = session.ratingDraftScoreTenths
        mutationInFlight = true
        sessionState.update { it.copy(isSavingRating = true, transientMessage = "") }
        viewModelScope.launch {
            try {
                rankingRepository.saveScore(step.song.id, scoreTenths)
                    .onSuccess {
                        advanceRatingStep()
                    }
                    .onFailure {
                        sessionState.update { state ->
                            state.copy(
                                isSavingRating = false,
                                transientMessage = "Could not save score. Try again."
                            )
                        }
                    }
            } finally {
                mutationInFlight = false
            }
        }
    }

    fun skipRatingStep() {
        if (mutationInFlight) return
        advanceRatingStep()
    }

    private fun advanceRatingStep() {
        sessionState.update { state ->
            val remaining = state.pendingRatingSteps.drop(1)
            state.copy(
                pendingRatingSteps = remaining,
                ratingDraftScoreTenths = remaining.firstOrNull()?.song?.scoreTenths ?: 55,
                ratingTotalSteps = if (remaining.isEmpty()) 0 else state.ratingTotalSteps,
                isSavingRating = false,
                visualFeedback = if (remaining.isEmpty()) RankVisualFeedback.None else state.visualFeedback,
                transientMessage = ""
            )
        }
        if (sessionState.value.pendingRatingSteps.isEmpty()) {
            maybeStartArmedAutoplay()
        }
    }

    fun continueAnyway() {
        sessionState.update { it.copy(continueAnyway = true, transientMessage = "") }
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
                                pendingRatingSteps = emptyList(),
                                ratingDraftScoreTenths = 55,
                                ratingTotalSteps = 0,
                                isSavingRating = false,
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
        if (uiState.value.ratingStep != null) return
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

private fun Matchup.postMatchRatingSteps(
    winnerId: String,
    loserId: String
): List<PendingPostMatchRating> = buildList {
    listOf(left, right).firstOrNull { it.id == winnerId && it.scoreTenths == null }?.let {
        add(PendingPostMatchRating(it, PostMatchRatingRole.Winner))
    }
    listOf(left, right).firstOrNull { it.id == loserId && it.scoreTenths == null }?.let {
        add(PendingPostMatchRating(it, PostMatchRatingRole.Loser))
    }
}

private fun Matchup?.hasSamePairAs(other: Matchup): Boolean {
    if (this == null) return false
    return setOf(left.rankingSubjectId, right.rankingSubjectId) ==
        setOf(other.left.rankingSubjectId, other.right.rankingSubjectId)
}

private data object UnavailablePreviewResolver : SongPreviewResolver {
    override suspend fun resolve(song: Song): String? = null
}

private data object NoOpPreviewPlayer : SongPreviewPlayer {
    override val events = kotlinx.coroutines.flow.emptyFlow<com.songladder.android.domain.repository.SongPreviewPlaybackEvent>()
    override fun play(songId: String, url: String) = Unit
    override fun pause() = Unit
    override fun stop() = Unit
}

private data object DefaultRankSettingsRepository : SettingsRepository {
    override fun observeSettings() = kotlinx.coroutines.flow.flowOf(RankingSettings())
    override suspend fun saveSettings(settings: RankingSettings): Result<Unit> = Result.success(Unit)
}
