package com.songladder.android.ui.rank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.Song
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

data class RankUiState(
    val songs: List<Song> = emptyList(),
    val stats: AppStats = AppStats(),
    val matchup: Matchup? = null,
    val message: String = "",
    val isReady: Boolean = false,
    val caughtUp: Boolean = false,
    val undoAvailable: Boolean = false,
    val undoStatus: UndoStatus = UndoStatus.None,
    val visualFeedback: RankVisualFeedback = RankVisualFeedback.None,
    val streakCount: Int = 0,
    val previews: Map<String, SongPreviewState> = emptyMap()
)

private data class RankSessionState(
    val previousMatchup: Matchup? = null,
    val continueAnyway: Boolean = false,
    val undoAvailable: Boolean = false,
    val undoStatus: UndoStatus = UndoStatus.None,
    val visualFeedback: RankVisualFeedback = RankVisualFeedback.None,
    val streakCount: Int = 0,
    val transientMessage: String = ""
)

class RankViewModel(
    private val songRepository: SongRepository,
    private val rankingRepository: RankingRepository,
    private val songPreviewResolver: SongPreviewResolver = UnavailablePreviewResolver,
    private val songPreviewPlayer: SongPreviewPlayer = NoOpPreviewPlayer
) : ViewModel() {
    private val matchupEngine = EloMatchupEngine()
    private val sessionState = MutableStateFlow(RankSessionState())
    private val previewStates = MutableStateFlow<Map<String, SongPreviewState>>(emptyMap())
    private val previewUrls = mutableMapOf<String, String>()
    private var clearFeedbackJob: Job? = null
    private var previewPrefetchJob: Job? = null
    private var previewPrefetchGeneration: Long = 0
    private var mutationInFlight = false

    private val rankingUiState: StateFlow<RankUiState> = combine(
        songRepository.observeSongs(),
        rankingRepository.observeStats(),
        rankingRepository.observeMatchupEvents(),
        sessionState
    ) { songs, stats, events, session ->
        val selection = if (session.visualFeedback == RankVisualFeedback.None) {
            matchupEngine.selectMatchup(
                songs = songs,
                events = events,
                previousMatchup = session.previousMatchup,
                continueAnyway = session.continueAnyway
            )
        } else {
            com.songladder.android.domain.model.MatchupSelection(session.previousMatchup)
        }
        val matchup = selection.matchup
        RankUiState(
            songs = songs,
            stats = stats,
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
            streakCount = session.streakCount
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankUiState())

    val uiState: StateFlow<RankUiState> = combine(rankingUiState, previewStates) { state, previews ->
        state.copy(previews = previews)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankUiState())

    init {
        viewModelScope.launch {
            songPreviewPlayer.events.collect { event ->
                previewStates.update { states ->
                    states + (event.songId to if (event.failed) SongPreviewState.Unavailable else SongPreviewState.Available)
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

    private fun prefetchPreviews(matchup: Matchup) {
        val generation = ++previewPrefetchGeneration
        previewPrefetchJob?.cancel()
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
                }
            }
        }
    }

    fun clearPreviewPrefetch() {
        previewPrefetchGeneration += 1
        previewPrefetchJob?.cancel()
        previewPrefetchJob = null
        previewStates.value = emptyMap()
    }

    fun rankWinner(winnerId: String, loserId: String) {
        if (mutationInFlight) return
        mutationInFlight = true
        stopPreview()
        viewModelScope.launch {
            try {
                val currentMatchup = uiState.value.matchup ?: return@launch
                rankingRepository.recordBattle(winnerId, loserId)
                    .onSuccess {
                        sessionState.update {
                            it.copy(
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
        mutationInFlight = true
        stopPreview()
        viewModelScope.launch {
            try {
                val currentMatchup = uiState.value.matchup ?: return@launch
                rankingRepository.recordSkip(listOf(currentMatchup.left.id, currentMatchup.right.id))
                    .onSuccess {
                        sessionState.update {
                            it.copy(
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
                songPreviewPlayer.pause()
                previewStates.update { it + (songId to SongPreviewState.Available) }
            }
            SongPreviewState.Available -> {
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
                        previewStates.update { it + (songId to SongPreviewState.Available) }
                    }
            }
            else -> Unit
        }
    }

    fun stopPreview() {
        songPreviewPlayer.stop()
        previewStates.update { states ->
            states.mapValues { (_, state) ->
                if (state == SongPreviewState.Playing) SongPreviewState.Available else state
            }
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

private data object UnavailablePreviewResolver : SongPreviewResolver {
    override suspend fun resolve(song: Song): String? = null
}

private data object NoOpPreviewPlayer : SongPreviewPlayer {
    override val events = kotlinx.coroutines.flow.emptyFlow<com.songladder.android.domain.repository.SongPreviewPlaybackEvent>()
    override fun play(songId: String, url: String) = Unit
    override fun pause() = Unit
    override fun stop() = Unit
}
