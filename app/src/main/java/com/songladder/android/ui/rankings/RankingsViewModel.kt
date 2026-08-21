package com.songladder.android.ui.rankings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.domain.model.RankingPresentation
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.ScoreSaveResult
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.model.Suggestion
import com.songladder.android.domain.model.scoreFirstComparator
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SettingsRepository
import com.songladder.android.domain.repository.SongPreviewPlaybackEvent
import com.songladder.android.domain.repository.SongPreviewPlayer
import com.songladder.android.domain.repository.SongPreviewResolver
import com.songladder.android.domain.repository.SongRepository
import com.songladder.android.ui.NoOpPreviewPlayer
import com.songladder.android.ui.UnavailablePreviewResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RankingsTab {
    SONGS,
    ALBUMS,
    ARTISTS
}

enum class RankingsPreviewState {
    Loading,
    Available,
    Playing,
    Unavailable
}

sealed interface RankingsStatus {
    data object None : RankingsStatus
    data object ComingSoon : RankingsStatus
    data object SaveFailed : RankingsStatus
    data object DeleteFailed : RankingsStatus
    data object UndoDeleteFailed : RankingsStatus
    data class ScoreSaved(val result: ScoreSaveResult) : RankingsStatus
    data class DeletedSong(val title: String) : RankingsStatus
}

data class RankedSong(
    val rank: Int,
    val song: Song
)

data class SuggestionRow(
    val suggestion: Suggestion,
    val song: Song
)

data class RankingsUiState(
    val allSongs: List<Song> = emptyList(),
    val rankedSongs: List<RankedSong> = emptyList(),
    val unratedSongs: List<Song> = emptyList(),
    val suggestionRows: List<SuggestionRow> = emptyList(),
    val selectedSuggestionIds: Set<String> = emptySet(),
    val selectedTab: RankingsTab = RankingsTab.SONGS,
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val settings: RankingSettings = RankingSettings(),
    val presentation: RankingPresentation = RankingPresentation.GRID,
    val unratedExpanded: Boolean = true,
    val expandedSongIds: Set<String> = emptySet(),
    val detailSongId: String? = null,
    val previews: Map<String, RankingsPreviewState> = emptyMap(),
    val isSavingScore: Boolean = false,
    val pendingDeletedSong: PendingDeletedSong? = null,
    val status: RankingsStatus = RankingsStatus.None
) {
    val detailSong: Song?
        get() = allSongs.firstOrNull { it.id == detailSongId }
}

private data class RankingsLocalState(
    val selectedTab: RankingsTab = RankingsTab.SONGS,
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val unratedExpanded: Boolean = false,
    val expandedSongIds: Set<String> = emptySet(),
    val selectedSuggestionIds: Set<String> = emptySet(),
    val detailSongId: String? = null,
    val isSavingScore: Boolean = false,
    val pendingDeletedSong: PendingDeletedSong? = null,
    val status: RankingsStatus = RankingsStatus.None
)

data class PendingDeletedSong(
    val rankingSubjectId: String,
    val input: SongInput
)

class RankingsViewModel(
    private val songRepository: SongRepository,
    private val rankingRepository: RankingRepository,
    private val settingsRepository: SettingsRepository,
    private val songPreviewResolver: SongPreviewResolver = UnavailablePreviewResolver,
    private val songPreviewPlayer: SongPreviewPlayer = NoOpPreviewPlayer
) : ViewModel() {
    private val localState = MutableStateFlow(RankingsLocalState())
    private val previewStates = MutableStateFlow<Map<String, RankingsPreviewState>>(emptyMap())
    private val previewUrls = mutableMapOf<String, String>()
    private var previewJob: Job? = null
    private var undoDeleteJob: Job? = null

    private val rankingState = combine(
        songRepository.observeSongs(),
        settingsRepository.observeSettings(),
        rankingRepository.observeSuggestions(),
        localState
    ) { songs, settings, suggestions, local ->
        val filtered = songs.filterByQuery(local.searchQuery)
        val ranked = filtered
            .filter { it.scoreTenths != null }
            .sortedWith(scoreFirstComparator())
            .mapIndexed { index, song -> RankedSong(rank = index + 1, song = song) }
        val unrated = filtered
            .filter { it.scoreTenths == null }
            .sortedByDescending { it.createdAt }
        val songsBySubjectId = songs.associateBy { it.rankingSubjectId }
        val suggestionRows = suggestions.mapNotNull { suggestion ->
            songsBySubjectId[suggestion.subjectId]?.let { song -> SuggestionRow(suggestion, song) }
        }
        RankingsUiState(
            allSongs = songs,
            rankedSongs = ranked,
            unratedSongs = unrated,
            suggestionRows = suggestionRows,
            selectedSuggestionIds = local.selectedSuggestionIds.intersect(
                suggestionRows.mapTo(mutableSetOf()) { it.suggestion.subjectId }
            ),
            selectedTab = local.selectedTab,
            searchActive = local.searchActive,
            searchQuery = local.searchQuery,
            settings = settings,
            presentation = settings.presentation,
            unratedExpanded = if (ranked.isEmpty()) true else local.unratedExpanded,
            expandedSongIds = local.expandedSongIds.intersect(songs.mapTo(mutableSetOf()) { it.id }),
            detailSongId = local.detailSongId?.takeIf { id -> songs.any { it.id == id } },
            isSavingScore = local.isSavingScore,
            pendingDeletedSong = local.pendingDeletedSong,
            status = local.status
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankingsUiState())

    val uiState: StateFlow<RankingsUiState> = combine(rankingState, previewStates) { state, previews ->
        state.copy(previews = previews)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankingsUiState())

    init {
        viewModelScope.launch {
            songPreviewPlayer.events.collect { event ->
                previewStates.update { states ->
                    states + (event.songId to if (event.failed) RankingsPreviewState.Unavailable else RankingsPreviewState.Available)
                }
            }
        }
    }

    fun selectTab(tab: RankingsTab) {
        localState.update {
            it.copy(
                selectedTab = tab,
                searchActive = if (tab == RankingsTab.SONGS) it.searchActive else false,
                searchQuery = if (tab == RankingsTab.SONGS) it.searchQuery else "",
                status = if (tab == RankingsTab.SONGS) RankingsStatus.None else RankingsStatus.ComingSoon
            )
        }
    }

    fun setSearchActive(active: Boolean) {
        localState.update {
            it.copy(
                searchActive = active,
                searchQuery = if (active) it.searchQuery else "",
                status = RankingsStatus.None
            )
        }
    }

    fun updateSearchQuery(query: String) {
        localState.update { it.copy(searchQuery = query, status = RankingsStatus.None) }
    }

    fun setPresentation(presentation: RankingPresentation) {
        val current = uiState.value.presentation
        if (presentation == current) return
        viewModelScope.launch {
            settingsRepository.saveSettings(uiState.value.settings.copy(presentation = presentation))
                .onFailure {
                    localState.update { state -> state.copy(status = RankingsStatus.SaveFailed) }
                }
        }
    }

    fun dismissRankingsTip() {
        val currentSettings = uiState.value.settings
        if (!currentSettings.showTips) return
        viewModelScope.launch {
            settingsRepository.saveSettings(currentSettings.copy(showTips = false))
                .onFailure {
                    localState.update { state -> state.copy(status = RankingsStatus.SaveFailed) }
                }
        }
    }

    fun toggleUnratedExpanded() {
        localState.update { it.copy(unratedExpanded = !uiState.value.unratedExpanded) }
    }

    fun toggleStats(songId: String) {
        localState.update { state ->
            state.copy(
                expandedSongIds = if (songId in state.expandedSongIds) {
                    state.expandedSongIds - songId
                } else {
                    state.expandedSongIds + songId
                }
            )
        }
    }

    fun showDetails(songId: String) {
        localState.update { it.copy(detailSongId = songId, status = RankingsStatus.None) }
    }

    fun hideDetails() {
        localState.update { it.copy(detailSongId = null) }
    }

    fun saveScore(songId: String, scoreTenths: Int) {
        if (uiState.value.isSavingScore) return
        localState.update { it.copy(isSavingScore = true, status = RankingsStatus.None) }
        viewModelScope.launch {
            rankingRepository.saveScore(songId, scoreTenths)
                .onSuccess { result ->
                    localState.update { state ->
                        state.copy(isSavingScore = false, status = RankingsStatus.ScoreSaved(result))
                    }
                }
                .onFailure {
                    localState.update { state ->
                        state.copy(isSavingScore = false, status = RankingsStatus.SaveFailed)
                    }
                }
        }
    }

    fun acceptSuggestion(subjectId: String, scoreTenths: Int) {
        if (uiState.value.isSavingScore) return
        localState.update { it.copy(isSavingScore = true, status = RankingsStatus.None) }
        viewModelScope.launch {
            rankingRepository.acceptSuggestion(subjectId, scoreTenths)
                .onSuccess { result ->
                    localState.update { state ->
                        state.copy(
                            isSavingScore = false,
                            selectedSuggestionIds = state.selectedSuggestionIds - subjectId,
                            status = RankingsStatus.ScoreSaved(result)
                        )
                    }
                }
                .onFailure {
                    localState.update { state -> state.copy(isSavingScore = false, status = RankingsStatus.SaveFailed) }
                }
        }
    }

    fun dismissSuggestionLater(subjectId: String) {
        val row = uiState.value.suggestionRows.firstOrNull { it.suggestion.subjectId == subjectId } ?: return
        val previousSelection = localState.value.selectedSuggestionIds
        localState.update { it.copy(selectedSuggestionIds = it.selectedSuggestionIds - subjectId) }
        viewModelScope.launch {
            rankingRepository.dismissSuggestionLater(
                subjectId = subjectId,
                suggestedScoreTenths = row.suggestion.suggestedScoreTenths,
                lastEventSequenceId = row.suggestion.lastEventSequenceId
            ).onFailure {
                localState.update { state ->
                    state.copy(selectedSuggestionIds = previousSelection, status = RankingsStatus.SaveFailed)
                }
            }
        }
    }

    fun toggleSuggestionSelection(subjectId: String) {
        localState.update { state ->
            state.copy(
                selectedSuggestionIds = if (subjectId in state.selectedSuggestionIds) {
                    state.selectedSuggestionIds - subjectId
                } else {
                    state.selectedSuggestionIds + subjectId
                }
            )
        }
    }

    fun clearSuggestionSelection() {
        localState.update { it.copy(selectedSuggestionIds = emptySet()) }
    }

    fun acceptSelectedSuggestions() {
        val selected = uiState.value.suggestionRows.filter { it.suggestion.subjectId in uiState.value.selectedSuggestionIds }
        if (selected.isEmpty() || uiState.value.isSavingScore) return
        localState.update { it.copy(isSavingScore = true, status = RankingsStatus.None) }
        viewModelScope.launch {
            var failed = false
            selected.forEach { row ->
                rankingRepository.acceptSuggestion(row.suggestion.subjectId, row.suggestion.suggestedScoreTenths)
                    .onFailure { failed = true }
            }
            localState.update { state ->
                state.copy(
                    isSavingScore = false,
                    selectedSuggestionIds = emptySet(),
                    status = if (failed) RankingsStatus.SaveFailed else RankingsStatus.None
                )
            }
        }
    }

    fun deleteSong(song: Song) {
        viewModelScope.launch {
            songRepository.removeSong(song.id)
                .onSuccess {
                    val pending = PendingDeletedSong(
                        rankingSubjectId = song.rankingSubjectId,
                        input = SongInput(
                            title = song.title,
                            artist = song.artist,
                            album = song.album,
                            artworkUrl = song.artworkUrl,
                            sourceType = song.sourceType,
                            externalId = song.externalId
                        )
                    )
                    undoDeleteJob?.cancel()
                    localState.update { state ->
                        state.copy(
                            detailSongId = null,
                            pendingDeletedSong = pending,
                            status = RankingsStatus.DeletedSong(song.title)
                        )
                    }
                    undoDeleteJob = viewModelScope.launch {
                        delay(10_000)
                        localState.update { state ->
                            if (state.pendingDeletedSong == pending) {
                                state.copy(pendingDeletedSong = null, status = RankingsStatus.None)
                            } else {
                                state
                            }
                        }
                    }
                }
                .onFailure {
                    localState.update { state -> state.copy(status = RankingsStatus.DeleteFailed) }
                }
        }
    }

    fun undoDelete() {
        val pending = uiState.value.pendingDeletedSong ?: return
        undoDeleteJob?.cancel()
        viewModelScope.launch {
            songRepository.restoreSong(pending.input, pending.rankingSubjectId)
                .onSuccess {
                    localState.update { state ->
                        state.copy(pendingDeletedSong = null, status = RankingsStatus.None)
                    }
                }
                .onFailure {
                    localState.update { state -> state.copy(status = RankingsStatus.UndoDeleteFailed) }
                }
        }
    }

    fun togglePreview(songId: String) {
        when (previewStates.value[songId]) {
            RankingsPreviewState.Playing -> {
                songPreviewPlayer.pause()
                previewStates.update { it + (songId to RankingsPreviewState.Available) }
            }
            RankingsPreviewState.Available -> playResolvedPreview(songId)
            RankingsPreviewState.Loading -> Unit
            RankingsPreviewState.Unavailable -> resolveAndPlay(songId)
            null -> resolveAndPlay(songId)
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        songPreviewPlayer.stop()
        previewStates.update { states ->
            states.mapValues { (_, state) ->
                if (state == RankingsPreviewState.Playing) RankingsPreviewState.Available else state
            }
        }
    }

    private fun resolveAndPlay(songId: String) {
        val song = uiState.value.allSongs.firstOrNull { it.id == songId } ?: return
        previewJob?.cancel()
        previewStates.update { it + (songId to RankingsPreviewState.Loading) }
        previewJob = viewModelScope.launch {
            val url = songPreviewResolver.resolve(song)
            if (url == null) {
                previewUrls.remove(songId)
                previewStates.update { it + (songId to RankingsPreviewState.Unavailable) }
                return@launch
            }
            previewUrls[songId] = url
            playResolvedPreview(songId)
        }
    }

    private fun playResolvedPreview(songId: String) {
        val url = previewUrls[songId] ?: return resolveAndPlay(songId)
        runCatching { songPreviewPlayer.play(songId, url) }
            .onSuccess {
                previewStates.update { states ->
                    states.mapValues { (id, state) ->
                        when {
                            id == songId -> RankingsPreviewState.Playing
                            state == RankingsPreviewState.Playing -> RankingsPreviewState.Available
                            else -> state
                        }
                    } + (songId to RankingsPreviewState.Playing)
                }
            }
            .onFailure {
                previewStates.update { it + (songId to RankingsPreviewState.Available) }
            }
    }
}

private fun List<Song>.filterByQuery(query: String): List<Song> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return this
    return filter { song ->
        song.title.contains(normalizedQuery, ignoreCase = true) ||
            song.artist.contains(normalizedQuery, ignoreCase = true) ||
            song.album.contains(normalizedQuery, ignoreCase = true)
    }
}
