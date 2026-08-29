package com.songladder.android.ui.rankings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.domain.model.Album
import com.songladder.android.domain.model.AlbumDetail
import com.songladder.android.domain.model.AlbumMatchStatus
import com.songladder.android.domain.model.AlbumReleaseCandidate
import com.songladder.android.domain.model.RankedAlbum
import com.songladder.android.domain.model.RankingPresentation
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.ScoreSaveResult
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.model.Suggestion
import com.songladder.android.domain.model.albumScoreFirstComparator
import com.songladder.android.domain.model.scoreFirstComparator
import com.songladder.android.domain.repository.AlbumRepository
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SettingsRepository
import com.songladder.android.domain.repository.SongPreviewPlaybackEvent
import com.songladder.android.domain.repository.SongPreviewPlayer
import com.songladder.android.domain.repository.SongPreviewResolver
import com.songladder.android.domain.repository.SongRepository
import com.songladder.android.ui.NoOpPreviewPlayer
import com.songladder.android.ui.UnavailablePreviewResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

data class AlbumMatchCandidatesState(
    val albumId: String,
    val isLoading: Boolean,
    val candidates: List<AlbumReleaseCandidate> = emptyList(),
    val error: Boolean = false
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
    val rankedAlbums: List<RankedAlbum> = emptyList(),
    val incompleteAlbums: List<RankedAlbum> = emptyList(),
    val incompleteAlbumsExpanded: Boolean = true,
    val detailSongId: String? = null,
    val detailAlbumId: String? = null,
    val albumDetail: AlbumDetail? = null,
    val albumMatchCandidates: AlbumMatchCandidatesState? = null,
    val previews: Map<String, RankingsPreviewState> = emptyMap(),
    val isSavingScore: Boolean = false,
    val isRefreshingAllAlbums: Boolean = false,
    val pendingDeletedSong: PendingDeletedSong? = null,
    val status: RankingsStatus = RankingsStatus.None
) {
    val detailSong: Song?
        get() = allSongs.firstOrNull { it.id == detailSongId }

    val albumsNeedingReview: List<RankedAlbum>
        get() = (rankedAlbums + incompleteAlbums).filter { it.album.matchStatus == AlbumMatchStatus.NEEDS_REVIEW }
}

private data class RankingsLocalState(
    val selectedTab: RankingsTab = RankingsTab.SONGS,
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val unratedExpanded: Boolean = false,
    val expandedSongIds: Set<String> = emptySet(),
    val incompleteAlbumsExpanded: Boolean = false,
    val selectedSuggestionIds: Set<String> = emptySet(),
    val detailSongId: String? = null,
    val detailAlbumId: String? = null,
    val isSavingScore: Boolean = false,
    val isRefreshingAllAlbums: Boolean = false,
    val dismissingSuggestionIds: Set<String> = emptySet(),
    val pendingDeletedSong: PendingDeletedSong? = null,
    val status: RankingsStatus = RankingsStatus.None
)

data class PendingDeletedSong(
    val rankingSubjectId: String,
    val input: SongInput
)

@OptIn(ExperimentalCoroutinesApi::class)
class RankingsViewModel(
    private val songRepository: SongRepository,
    private val rankingRepository: RankingRepository,
    private val albumRepository: AlbumRepository,
    private val settingsRepository: SettingsRepository,
    private val songPreviewResolver: SongPreviewResolver = UnavailablePreviewResolver,
    private val songPreviewPlayer: SongPreviewPlayer = NoOpPreviewPlayer
) : ViewModel() {
    private val localState = MutableStateFlow(RankingsLocalState())
    private val previewStates = MutableStateFlow<Map<String, RankingsPreviewState>>(emptyMap())
    private val previewUrls = mutableMapOf<String, String>()
    private val albumCandidatesState = MutableStateFlow<AlbumMatchCandidatesState?>(null)
    private var previewJob: Job? = null
    private var undoDeleteJob: Job? = null

    // Only observed (a richer per-album query than the list-level RankedAlbum flow
    // carries) while the detail dialog is actually open, and switches cleanly to a
    // new album's flow - or back to null - the moment detailAlbumId changes. Shared
    // as a StateFlow (not a plain cold Flow) because it has two independent
    // collectors below - the uiState combine and the match-candidates side effect in
    // init{} - and a cold flow would re-run flatMapLatest, and so re-subscribe to
    // albumRepository.observeAlbumDetail, once per collector.
    private val albumDetailFlow: StateFlow<AlbumDetail?> = localState
        .map { it.detailAlbumId }
        .distinctUntilChanged()
        .flatMapLatest { albumId -> if (albumId == null) flowOf(null) else albumRepository.observeAlbumDetail(albumId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Kept separate from `localState` below so that a UI-local change (e.g. a search
    // keystroke) doesn't force songsBySubjectId/suggestionRows to be rebuilt - this
    // combine only recomputes when songs or suggestions actually change.
    private val songsAndSuggestionRows = combine(
        songRepository.observeSongs(),
        rankingRepository.observeSuggestions()
    ) { songs, suggestions ->
        val songsBySubjectId = songs.associateBy { it.rankingSubjectId }
        val suggestionRows = suggestions.mapNotNull { suggestion ->
            songsBySubjectId[suggestion.subjectId]?.let { song -> SuggestionRow(suggestion, song) }
        }
        songs to suggestionRows
    }

    private val rankingState = combine(
        songsAndSuggestionRows,
        settingsRepository.observeSettings(),
        localState,
        albumRepository.observeAlbums()
    ) { (songs, suggestionRows), settings, local, albums ->
        val ranked = songs
            .filter { it.scoreTenths != null }
            .sortedWith(scoreFirstComparator())
            .mapIndexed { index, song -> RankedSong(rank = index + 1, song = song) }
            .filterByQuery(local.searchQuery) { rankedSong, q -> rankedSong.song.matchesQuery(q) }
        val unrated = songs
            .filter { it.scoreTenths == null }
            .sortedByDescending { it.createdAt }
            .filterByQuery(local.searchQuery) { song, q -> song.matchesQuery(q) }
        val rankedAlbums = albums
            .filter { it.scoreTenths != null }
            .sortedWith(albumScoreFirstComparator())
            .mapIndexed { index, rankedAlbum -> rankedAlbum.copy(rank = index + 1) }
            .filterByQuery(local.searchQuery) { rankedAlbum, q -> rankedAlbum.album.matchesQuery(q) }
        val incompleteAlbums = albums
            .filter { it.scoreTenths == null }
            .sortedByDescending { it.album.createdAt }
            .filterByQuery(local.searchQuery) { rankedAlbum, q -> rankedAlbum.album.matchesQuery(q) }
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
            rankedAlbums = rankedAlbums,
            incompleteAlbums = incompleteAlbums,
            incompleteAlbumsExpanded = if (rankedAlbums.isEmpty()) true else local.incompleteAlbumsExpanded,
            detailSongId = local.detailSongId?.takeIf { id -> songs.any { it.id == id } },
            detailAlbumId = local.detailAlbumId,
            isSavingScore = local.isSavingScore,
            isRefreshingAllAlbums = local.isRefreshingAllAlbums,
            pendingDeletedSong = local.pendingDeletedSong,
            status = local.status
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankingsUiState())

    val uiState: StateFlow<RankingsUiState> = combine(
        rankingState,
        previewStates,
        albumDetailFlow,
        albumCandidatesState
    ) { state, previews, albumDetail, albumMatchCandidates ->
        state.copy(previews = previews, albumDetail = albumDetail, albumMatchCandidates = albumMatchCandidates)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankingsUiState())

    init {
        viewModelScope.launch {
            songPreviewPlayer.events.collect { event ->
                previewStates.update { states ->
                    states + (event.songId to if (event.failed) RankingsPreviewState.Unavailable else RankingsPreviewState.Available)
                }
            }
        }
        viewModelScope.launch {
            albumDetailFlow.collect { detail ->
                if (detail?.album?.matchStatus == AlbumMatchStatus.NEEDS_REVIEW) {
                    loadAlbumMatchCandidatesIfNeeded(detail.album.id)
                } else {
                    albumCandidatesState.value = null
                }
            }
        }
    }

    fun selectTab(tab: RankingsTab) {
        localState.update {
            val tabChanged = tab != it.selectedTab
            it.copy(
                selectedTab = tab,
                searchActive = if (tabChanged) false else it.searchActive,
                searchQuery = if (tabChanged) "" else it.searchQuery,
                status = if (tab == RankingsTab.ARTISTS) RankingsStatus.ComingSoon else RankingsStatus.None
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

    fun toggleIncompleteAlbumsExpanded() {
        localState.update { it.copy(incompleteAlbumsExpanded = !uiState.value.incompleteAlbumsExpanded) }
    }

    fun showAlbumDetails(albumId: String) {
        localState.update { it.copy(detailAlbumId = albumId, status = RankingsStatus.None) }
    }

    fun hideAlbumDetails() {
        localState.update { it.copy(detailAlbumId = null) }
    }

    fun setAlbumTrackExcluded(albumId: String, songId: String, excluded: Boolean) {
        viewModelScope.launch {
            albumRepository.setTrackExcluded(albumId, songId, excluded)
                .onFailure { localState.update { it.copy(status = RankingsStatus.SaveFailed) } }
        }
    }

    fun addAlbumMissingTracks(albumId: String, providerTrackIds: List<String>) {
        if (providerTrackIds.isEmpty()) return
        viewModelScope.launch {
            albumRepository.addMissingTracks(albumId, providerTrackIds)
                .onFailure { localState.update { it.copy(status = RankingsStatus.SaveFailed) } }
        }
    }

    fun chooseAlbumRelease(albumId: String, providerCollectionId: String) {
        viewModelScope.launch {
            albumRepository.chooseRelease(albumId, providerCollectionId)
                .onFailure { localState.update { it.copy(status = RankingsStatus.SaveFailed) } }
        }
    }

    fun refreshAlbumMetadata(albumId: String) {
        viewModelScope.launch {
            albumRepository.refreshMetadata(albumId)
                // Cleared rather than reloaded inline: the collector in init{} re-issues
                // a fresh search as soon as the next albumDetailFlow emission lands, which
                // naturally picks up whatever matchStatus the refresh actually produced.
                .onSuccess { albumCandidatesState.value = null }
                .onFailure { localState.update { it.copy(status = RankingsStatus.SaveFailed) } }
        }
    }

    fun refreshAllAlbumMetadata() {
        // Guards against localState directly (not uiState) since uiState is a step
        // behind it - it's only recomputed once the combine chain below re-collects,
        // so a second call issued before that happens would otherwise read a stale
        // "not refreshing" value and slip through.
        if (localState.value.isRefreshingAllAlbums) return
        localState.update { it.copy(isRefreshingAllAlbums = true, status = RankingsStatus.None) }
        viewModelScope.launch {
            albumRepository.refreshAllMetadata()
                .onSuccess { albumCandidatesState.value = null }
                .onFailure { localState.update { it.copy(status = RankingsStatus.SaveFailed) } }
            localState.update { it.copy(isRefreshingAllAlbums = false) }
        }
    }

    private fun loadAlbumMatchCandidatesIfNeeded(albumId: String) {
        if (albumCandidatesState.value?.albumId == albumId) return
        albumCandidatesState.value = AlbumMatchCandidatesState(albumId = albumId, isLoading = true)
        viewModelScope.launch {
            albumRepository.searchReleaseCandidates(albumId)
                .onSuccess { candidates ->
                    albumCandidatesState.update { current ->
                        if (current?.albumId == albumId) current.copy(isLoading = false, candidates = candidates) else current
                    }
                }
                .onFailure {
                    albumCandidatesState.update { current ->
                        if (current?.albumId == albumId) current.copy(isLoading = false, error = true) else current
                    }
                }
        }
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
        if (subjectId in localState.value.dismissingSuggestionIds) return
        val row = uiState.value.suggestionRows.firstOrNull { it.suggestion.subjectId == subjectId } ?: return
        val wasSelected = subjectId in localState.value.selectedSuggestionIds
        localState.update {
            it.copy(
                selectedSuggestionIds = it.selectedSuggestionIds - subjectId,
                dismissingSuggestionIds = it.dismissingSuggestionIds + subjectId
            )
        }
        viewModelScope.launch {
            try {
                rankingRepository.dismissSuggestionLater(
                    subjectId = subjectId,
                    suggestedScoreTenths = row.suggestion.suggestedScoreTenths,
                    lastEventSequenceId = row.suggestion.lastEventSequenceId
                ).onFailure {
                    localState.update { state ->
                        state.copy(
                            selectedSuggestionIds = if (wasSelected) state.selectedSuggestionIds + subjectId else state.selectedSuggestionIds,
                            status = RankingsStatus.SaveFailed
                        )
                    }
                }
            } finally {
                localState.update { it.copy(dismissingSuggestionIds = it.dismissingSuggestionIds - subjectId) }
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
            val accepts = selected.map { row -> row.suggestion.subjectId to row.suggestion.suggestedScoreTenths }
            rankingRepository.acceptSuggestions(accepts)
                .onSuccess {
                    localState.update { state ->
                        state.copy(isSavingScore = false, selectedSuggestionIds = emptySet(), status = RankingsStatus.None)
                    }
                }
                .onFailure {
                    localState.update { state -> state.copy(isSavingScore = false, status = RankingsStatus.SaveFailed) }
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

private fun Song.matchesQuery(normalizedQuery: String): Boolean {
    return title.contains(normalizedQuery, ignoreCase = true) ||
        artist.contains(normalizedQuery, ignoreCase = true) ||
        album.contains(normalizedQuery, ignoreCase = true)
}

private fun Album.matchesQuery(normalizedQuery: String): Boolean {
    return title.contains(normalizedQuery, ignoreCase = true) ||
        artist.contains(normalizedQuery, ignoreCase = true)
}

private inline fun <T> List<T>.filterByQuery(query: String, matches: (T, String) -> Boolean): List<T> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return this
    return filter { matches(it, normalizedQuery) }
}
