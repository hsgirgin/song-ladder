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
    data class Choice(
        val winnerId: String,
        val loserId: String,
        val winnerRatingChange: Int = 0,
        val loserRatingChange: Int = 0
    ) : RankVisualFeedback
    data object Skip : RankVisualFeedback
}

enum class RankMessage {
    None,
    NeedTwoSongs,
    HotStreak,
    BattleSaveFailed,
    SkipSaveFailed
}

enum class SongPreviewState {
    Loading,
    Available,
    Playing,
    Unavailable
}

data class RankUiState(
    val songs: List<Song> = emptyList(),
    val stats: AppStats = AppStats(),
    val matchup: Matchup? = null,
    val message: RankMessage = RankMessage.None,
    val isReady: Boolean = false,
    val visualFeedback: RankVisualFeedback = RankVisualFeedback.None,
    val streakCount: Int = 0,
    val previews: Map<String, SongPreviewState> = emptyMap(),
    val isSaving: Boolean = false,
    val isFirstMatchupReady: Boolean = false
)

private data class RankSessionState(
    val previousMatchup: Matchup? = null,
    val visualFeedback: RankVisualFeedback = RankVisualFeedback.None,
    val streakCount: Int = 0,
    val transientMessage: RankMessage = RankMessage.None
)

class RankViewModel(
    private val songRepository: SongRepository,
    private val rankingRepository: RankingRepository,
    private val songPreviewResolver: SongPreviewResolver = UnavailablePreviewResolver,
    private val songPreviewPlayer: SongPreviewPlayer = NoOpPreviewPlayer,
    private val matchupEngine: EloMatchupEngine = EloMatchupEngine()
) : ViewModel() {
    private val sessionState = MutableStateFlow(RankSessionState())
    private val pendingMatchup = MutableStateFlow<Matchup?>(null)
    private val previewStates = MutableStateFlow<Map<String, SongPreviewState>>(emptyMap())
    private val previewUrls = mutableMapOf<String, String>()
    private var clearFeedbackJob: Job? = null
    private var previewPrefetchJob: Job? = null
    private var previewPrefetchGeneration: Long = 0

    private val rankingUiState: StateFlow<RankUiState> = combine(
        songRepository.observeSongs(),
        rankingRepository.observeStats(),
        sessionState,
        pendingMatchup
    ) { songs, stats, session, pending ->
        val matchup = pending ?: if (session.visualFeedback == RankVisualFeedback.None) {
            matchupEngine.pickMatchup(songs, session.previousMatchup)
        } else {
            session.previousMatchup
        }
        RankUiState(
            songs = songs,
            stats = stats,
            matchup = matchup,
            message = when {
                session.transientMessage != RankMessage.None -> session.transientMessage
                songs.size < 2 -> RankMessage.NeedTwoSongs
                session.streakCount >= 3 -> RankMessage.HotStreak
                else -> RankMessage.None
            },
            isReady = songs.size >= 2,
            visualFeedback = session.visualFeedback,
            streakCount = session.streakCount,
            isSaving = pending != null,
            isFirstMatchupReady = songs.size >= 2 && stats.matchCount == 0 && matchup != null
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
        val currentMatchup = uiState.value.matchup ?: return
        if (!tryStartSaveOperation(currentMatchup)) return
        stopPreview()
        viewModelScope.launch {
            try {
                rankingRepository.recordBattle(winnerId, loserId)
                    .onSuccess {
                        val winner = currentMatchup.songForId(winnerId) ?: return@onSuccess
                        val loser = currentMatchup.songForId(loserId) ?: return@onSuccess
                        val (winnerUpdate, loserUpdate) = matchupEngine.updateRatings(winner, loser)
                        sessionState.update {
                            it.copy(
                                previousMatchup = currentMatchup,
                                visualFeedback = RankVisualFeedback.Choice(
                                    winnerId = winnerId,
                                    loserId = loserId,
                                    winnerRatingChange = winnerUpdate.rating - winner.rating,
                                    loserRatingChange = loserUpdate.rating - loser.rating
                                ),
                                streakCount = it.streakCount + 1,
                                transientMessage = RankMessage.None
                            )
                        }
                        scheduleFeedbackClear()
                    }
                    .onFailure {
                        sessionState.update { state ->
                            state.copy(
                                visualFeedback = RankVisualFeedback.None,
                                transientMessage = RankMessage.BattleSaveFailed
                            )
                        }
                        scheduleFeedbackClear(delayMillis = 2_500)
                    }
            } finally {
                pendingMatchup.value = null
            }
        }
    }

    fun skip() {
        val currentMatchup = uiState.value.matchup ?: return
        if (!tryStartSaveOperation(currentMatchup)) return
        stopPreview()
        viewModelScope.launch {
            try {
                rankingRepository.recordSkip(listOf(currentMatchup.left.id, currentMatchup.right.id))
                    .onSuccess {
                        sessionState.update {
                            it.copy(
                                previousMatchup = currentMatchup,
                                visualFeedback = RankVisualFeedback.Skip,
                                streakCount = 0,
                                transientMessage = RankMessage.None
                            )
                        }
                        scheduleFeedbackClear()
                    }
                    .onFailure {
                        sessionState.update { state ->
                            state.copy(
                                visualFeedback = RankVisualFeedback.None,
                                transientMessage = RankMessage.SkipSaveFailed
                            )
                        }
                        scheduleFeedbackClear(delayMillis = 2_500)
                    }
            } finally {
                pendingMatchup.value = null
            }
        }
    }

    private fun tryStartSaveOperation(matchup: Matchup): Boolean {
        if (sessionState.value.visualFeedback != RankVisualFeedback.None) return false
        return pendingMatchup.compareAndSet(null, matchup)
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
            sessionState.update { it.copy(visualFeedback = RankVisualFeedback.None, transientMessage = RankMessage.None) }
        }
    }
}

private fun Matchup.songForId(songId: String): Song? {
    return when (songId) {
        left.id -> left
        right.id -> right
        else -> null
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
