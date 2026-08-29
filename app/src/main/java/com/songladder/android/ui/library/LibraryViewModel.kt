package com.songladder.android.ui.library

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.data.spotify.SpotifyPlaylistJsonImporter
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.PlaylistImportPreview
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.model.TombstoneImportAction
import com.songladder.android.domain.model.TombstoneImportConflict
import com.songladder.android.domain.model.TombstoneImportResolution
import com.songladder.android.domain.repository.ImportRepository
import com.songladder.android.domain.repository.MusicSourceClient
import com.songladder.android.domain.repository.PlaylistSourceClient
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SettingsRepository
import com.songladder.android.domain.repository.SongPreviewPlaybackEvent
import com.songladder.android.domain.repository.SongPreviewPlayer
import com.songladder.android.domain.repository.SongPreviewResolver
import com.songladder.android.domain.repository.SongRepository
import com.songladder.android.ui.NoOpPreviewPlayer
import com.songladder.android.ui.UnavailablePreviewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportRatingQueueKind {
    PLAYLIST,
    SINGLE_SONG
}

enum class ImportRatingQueuePreviewState {
    Loading,
    Available,
    Playing,
    Unavailable
}

data class ImportRatingQueueCompletion(
    val ratedCount: Int,
    val skippedCount: Int
)

data class ImportRatingQueueState(
    val kind: ImportRatingQueueKind,
    val songIds: List<String>,
    val currentIndex: Int = 0,
    val draftScoreTenths: Int = 55,
    val ratedCount: Int = 0,
    val skippedCount: Int = 0,
    val isSaving: Boolean = false,
    val autoplayArmed: Boolean = false,
    val previewState: ImportRatingQueuePreviewState = ImportRatingQueuePreviewState.Loading,
    val errorMessage: String? = null,
    val completion: ImportRatingQueueCompletion? = null
) {
    val currentSongId: String?
        get() = songIds.getOrNull(currentIndex)
}

data class LibraryUiState(
    val songs: List<Song> = emptyList(),
    val settings: RankingSettings = RankingSettings(),
    val searchQuery: String = "",
    val searchResults: List<MusicTrackCandidate> = emptyList(),
    val statusMessage: String = "",
    val isSearching: Boolean = false,
    val addingTrackIds: Set<String> = emptySet(),
    val addedTrackIds: Set<String> = emptySet(),
    val duplicateTrackIds: Set<String> = emptySet(),
    val youtubeMusicPlaylistUrl: String = "",
    val isPreviewLoading: Boolean = false,
    val youtubeMusicPreview: PlaylistImportPreview? = null,
    val spotifyPreview: PlaylistImportPreview? = null,
    val previewError: String? = null,
    val isImportingPreview: Boolean = false,
    val tombstoneConflict: TombstoneImportConflict? = null,
    val pendingImportCandidates: List<MusicTrackCandidate> = emptyList(),
    val pendingImportSourceLabel: String = "",
    val tombstoneResolutions: Map<String, TombstoneImportResolution> = emptyMap(),
    val jsonImportRepairedCount: Int? = null,
    val ratingQueue: ImportRatingQueueState? = null,
    val ambiguousMatch: AmbiguousSongMatch? = null
)

data class AmbiguousSongMatch(
    val candidate: MusicTrackCandidate,
    val matches: List<Song>
)

private data class SongLookupKey(
    val title: String,
    val artist: String,
    val sourceTypeName: String,
    val externalId: String?
)

private data class PendingRatingQueueLaunch(
    val kind: ImportRatingQueueKind,
    val songKeys: List<SongLookupKey>,
    val existingSongIds: Set<String>,
    val expectedCount: Int
)

@OptIn(FlowPreview::class)
class LibraryViewModel(
    private val songRepository: SongRepository,
    private val importRepository: ImportRepository,
    private val musicSourceClient: MusicSourceClient,
    private val playlistSourceClient: PlaylistSourceClient,
    private val rankingRepository: RankingRepository = DefaultLibraryRankingRepository,
    private val settingsRepository: SettingsRepository = DefaultLibrarySettingsRepository,
    private val songPreviewResolver: SongPreviewResolver = UnavailablePreviewResolver,
    private val songPreviewPlayer: SongPreviewPlayer = NoOpPreviewPlayer
) : ViewModel() {
    private val localState = MutableStateFlow(LibraryUiState())
    private var clearAddedStateJob: Job? = null
    private var queuePreviewJob: Job? = null
    private var queuePreviewGeneration: Long = 0
    private val queuePreviewUrls = mutableMapOf<String, String>()
    private var pendingRatingQueueLaunches: List<PendingRatingQueueLaunch> = emptyList()
    private var pendingRatingQueueLaunchWatcherJob: Job? = null
    private val spotifyPlaylistJsonImporter = SpotifyPlaylistJsonImporter()

    val uiState: StateFlow<LibraryUiState> = combine(
        songRepository.observeSongs(),
        settingsRepository.observeSettings(),
        localState
    ) { songs, settings, local ->
        local.copy(songs = songs, settings = settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        viewModelScope.launch {
            localState
                .map { it.searchQuery.trim() }
                .distinctUntilChanged()
                .debounce(400)
                .collectLatest { query ->
                    localState.update {
                        it.copy(
                            addedTrackIds = emptySet(),
                            duplicateTrackIds = emptySet()
                        )
                    }

                    if (query.isBlank()) {
                        localState.update {
                            it.copy(
                                searchResults = emptyList(),
                                isSearching = false
                            )
                        }
                        return@collectLatest
                    }

                    if (query.length < 2) {
                        localState.update {
                            it.copy(
                                searchResults = emptyList(),
                                isSearching = false,
                                statusMessage = "Keep typing to search iTunes."
                            )
                        }
                        return@collectLatest
                    }

                    performItunesSearch(query)
                }
        }
        viewModelScope.launch {
            songPreviewPlayer.events.collect { event ->
                handleQueuePreviewEvent(event)
            }
        }
    }

    fun addSong(title: String, artist: String, album: String) {
        viewModelScope.launch {
            val existingSongIds = currentSongIds()
            songRepository.addSong(SongInput(title = title, artist = artist, album = album))
                .onSuccess {
                    localState.update { it.copy(statusMessage = "Song added to your ladder.") }
                    scheduleRatingQueueLaunch(
                        kind = ImportRatingQueueKind.SINGLE_SONG,
                        songKeys = listOf(
                            SongLookupKey(
                                title = title.trim(),
                                artist = artist.trim(),
                                sourceTypeName = MusicSourceType.MANUAL.name,
                                externalId = null
                            )
                        ),
                        existingSongIds = existingSongIds,
                        expectedCount = 1
                    )
                }
                .onFailure { error ->
                    localState.update { it.copy(statusMessage = error.message ?: "Could not add song.") }
                }
        }
    }

    fun removeSong(songId: String) {
        viewModelScope.launch {
            songRepository.removeSong(songId)
                .onSuccess {
                    localState.update { it.copy(statusMessage = "Song removed.") }
                }
                .onFailure { error ->
                    localState.update { it.copy(statusMessage = error.message ?: "Could not remove song.") }
                }
        }
    }

    fun resetLibrary() {
        viewModelScope.launch {
            songRepository.resetLibrary()
                .onSuccess {
                    clearQueuePreview()
                    cancelPendingRatingQueueLaunches()
                    localState.update {
                        it.copy(
                            statusMessage = "Library reset.",
                            ratingQueue = null
                        )
                    }
                }
                .onFailure { error ->
                    localState.update { it.copy(statusMessage = error.message ?: "Could not reset library.") }
                }
        }
    }

    fun seedSampleSongs() {
        viewModelScope.launch {
            importRepository.seedSampleSongs()
                .onSuccess { count ->
                    localState.update {
                        it.copy(
                            statusMessage = if (count > 0) {
                                "Sample pack imported."
                            } else {
                                "Sample pack is already in your ladder."
                            }
                        )
                    }
                }
                .onFailure { error ->
                    localState.update { it.copy(statusMessage = error.message ?: "Sample pack import failed.") }
                }
        }
    }

    fun updateSearchQuery(query: String) {
        localState.update { it.copy(searchQuery = query) }
    }

    fun addSearchResult(candidate: MusicTrackCandidate) {
        viewModelScope.launch {
            val existingSongIds = currentSongIds()
            localState.update {
                it.copy(
                    addingTrackIds = it.addingTrackIds + candidate.externalId,
                    statusMessage = "Adding ${candidate.title}..."
                )
            }
            val conflicts = importRepository.findTombstoneMatches(listOf(candidate)).getOrElse { error ->
                localState.update {
                    it.copy(
                        addingTrackIds = it.addingTrackIds - candidate.externalId,
                        statusMessage = error.message ?: "Import failed."
                    )
                }
                return@launch
            }
            if (conflicts.isNotEmpty()) {
                localState.update {
                    it.copy(
                        tombstoneConflict = conflicts.first(),
                        pendingImportCandidates = listOf(candidate),
                        pendingImportSourceLabel = "iTunes search"
                    )
                }
            } else {
                val ambiguousMatches = songRepository.findAmbiguousMatches(candidate).getOrElse { emptyList() }
                if (ambiguousMatches.isNotEmpty()) {
                    localState.update {
                        it.copy(ambiguousMatch = AmbiguousSongMatch(candidate, ambiguousMatches))
                    }
                } else {
                    importSearchCandidate(candidate, existingSongIds)
                }
            }

            scheduleSearchCueReset()
        }
    }

    fun confirmAmbiguousMatchIsSameSong() {
        val match = localState.value.ambiguousMatch ?: return
        localState.update {
            it.copy(
                ambiguousMatch = null,
                addingTrackIds = it.addingTrackIds - match.candidate.externalId,
                statusMessage = "${match.candidate.title} is already in your ladder."
            )
        }
    }

    fun cancelAmbiguousMatch() {
        val match = localState.value.ambiguousMatch ?: return
        localState.update {
            it.copy(
                ambiguousMatch = null,
                addingTrackIds = it.addingTrackIds - match.candidate.externalId,
                statusMessage = "Import cancelled."
            )
        }
    }

    fun addAmbiguousMatchAsNew() {
        val match = localState.value.ambiguousMatch ?: return
        localState.update { it.copy(ambiguousMatch = null) }
        viewModelScope.launch {
            importSearchCandidate(match.candidate, currentSongIds())
            scheduleSearchCueReset()
        }
    }

    fun importJson(contentResolver: ContentResolver, uri: Uri) {
        importJson { importRepository.importFromJson(contentResolver, uri) }
    }

    internal fun importJson(importOperation: suspend () -> Result<Int>) {
        viewModelScope.launch {
            importOperation()
                .onSuccess { repairedCount ->
                    localState.update {
                        it.copy(
                            statusMessage = "",
                            jsonImportRepairedCount = repairedCount
                        )
                    }
                }
                .onFailure { error ->
                    localState.update {
                        it.copy(
                            statusMessage = error.message ?: "JSON import failed.",
                            jsonImportRepairedCount = null
                        )
                    }
                }
        }
    }

    fun exportJson(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            importRepository.exportToJson(contentResolver, uri)
                .onSuccess {
                    localState.update { it.copy(statusMessage = "Exported your library.") }
                }
                .onFailure { error ->
                    localState.update { it.copy(statusMessage = error.message ?: "Export failed.") }
                }
        }
    }

    fun updateYoutubeMusicPlaylistUrl(url: String) {
        localState.update {
            it.copy(
                youtubeMusicPlaylistUrl = url,
                previewError = null,
                youtubeMusicPreview = null
            )
        }
    }

    fun previewYoutubeMusicPlaylist() {
        viewModelScope.launch {
            val playlistUrl = localState.value.youtubeMusicPlaylistUrl.trim()
            if (playlistUrl.isBlank()) {
                localState.update { it.copy(previewError = "Paste a public YouTube Music playlist link.") }
                return@launch
            }

            localState.update {
                it.copy(
                    isPreviewLoading = true,
                    previewError = null,
                    youtubeMusicPreview = null
                )
            }

            playlistSourceClient.previewPlaylist(playlistUrl)
                .onSuccess { preview ->
                    localState.update {
                        it.copy(
                            isPreviewLoading = false,
                            youtubeMusicPreview = preview,
                            statusMessage = "Previewed ${preview.importableTracks.size} YouTube Music tracks."
                        )
                    }
                }
                .onFailure { error ->
                    localState.update {
                        it.copy(
                            isPreviewLoading = false,
                            previewError = error.message ?: "Could not preview this YouTube Music playlist."
                        )
                    }
                }
        }
    }

    fun confirmYoutubeMusicPreviewImport() {
        viewModelScope.launch {
            val preview = localState.value.youtubeMusicPreview ?: return@launch
            localState.update { it.copy(isImportingPreview = true, previewError = null) }
            val conflicts = importRepository.findTombstoneMatches(preview.importableTracks).getOrElse { error ->
                localState.update {
                    it.copy(
                        isImportingPreview = false,
                        previewError = error.message ?: "Could not import this playlist."
                    )
                }
                return@launch
            }
            if (conflicts.isNotEmpty()) {
                localState.update {
                    it.copy(
                        isImportingPreview = false,
                        tombstoneConflict = conflicts.first(),
                        pendingImportCandidates = preview.importableTracks,
                        pendingImportSourceLabel = "YouTube Music Playlist"
                    )
                }
            } else {
                importPlaylistTracks(preview.importableTracks, preview.playlistTitle)
            }
        }
    }

    fun resolveTombstoneConflict(resolution: TombstoneImportResolution) {
        viewModelScope.launch {
            val state = localState.value
            val conflict = state.tombstoneConflict ?: return@launch
            val resolutions = state.tombstoneResolutions + (importKey(conflict.candidate) to resolution)
            val nextConflict = importRepository.findTombstoneMatches(state.pendingImportCandidates)
                .getOrElse { error ->
                    localState.update { it.copy(statusMessage = error.message ?: "Import failed.") }
                    return@launch
                }
                .firstOrNull { importKey(it.candidate) !in resolutions }
            if (nextConflict != null) {
                localState.update { it.copy(tombstoneConflict = nextConflict, tombstoneResolutions = resolutions) }
            } else {
                val candidates = state.pendingImportCandidates
                val sourceLabel = state.pendingImportSourceLabel
                val existingSongIds = currentSongIds()
                localState.update { it.copy(tombstoneConflict = null, tombstoneResolutions = resolutions) }
                importRepository.importTracks(candidates, sourceLabel, resolutions)
                    .onSuccess { count ->
                        localState.update {
                            it.copy(
                                isImportingPreview = false,
                                addingTrackIds = it.addingTrackIds - candidates.map { candidate -> candidate.externalId }.toSet(),
                                addedTrackIds = it.addedTrackIds + candidates.map { candidate -> candidate.externalId },
                                pendingImportCandidates = emptyList(),
                                pendingImportSourceLabel = "",
                                tombstoneResolutions = emptyMap(),
                                youtubeMusicPreview = null,
                                spotifyPreview = null,
                                statusMessage = "Imported $count songs."
                            )
                        }
                        // Restored candidates get a freshly-inserted SongEntity id (re-pointed at the
                        // preserved RankingSubjectEntity), so they'd otherwise pass the "new song" filter
                        // in tryResolveNextPendingRatingQueueLaunch and get queued for rating even though
                        // their score/wins/losses were already restored. Only songs actually resolved as
                        // START_FRESH (or with no tombstone conflict at all) belong in the rating queue.
                        val ratableSongKeys = candidates
                            .filterNot { resolutions[importKey(it)]?.action == TombstoneImportAction.RESTORE }
                            .distinctSongLookupKeys()
                        val ratableCount = minOf(count, ratableSongKeys.size)
                        scheduleRatingQueueLaunch(
                            kind = if (ratableCount > 1) ImportRatingQueueKind.PLAYLIST else ImportRatingQueueKind.SINGLE_SONG,
                            songKeys = ratableSongKeys,
                            existingSongIds = existingSongIds,
                            expectedCount = ratableCount
                        )
                    }
                    .onFailure { error ->
                        localState.update {
                            it.copy(statusMessage = error.message ?: "Import failed.")
                        }
                    }
            }
        }
    }

    fun cancelTombstoneConflict() {
        localState.update {
            it.copy(
                tombstoneConflict = null,
                pendingImportCandidates = emptyList(),
                pendingImportSourceLabel = "",
                tombstoneResolutions = emptyMap(),
                isImportingPreview = false,
                statusMessage = "Import cancelled."
            )
        }
    }

    fun clearYoutubeMusicPreview() {
        localState.update {
            it.copy(
                youtubeMusicPreview = null,
                previewError = null,
                isPreviewLoading = false,
                isImportingPreview = false
            )
        }
    }

    fun confirmSpotifyPreviewImport() {
        viewModelScope.launch {
            val preview = localState.value.spotifyPreview ?: return@launch
            localState.update { it.copy(isImportingPreview = true, previewError = null) }
            val conflicts = importRepository.findTombstoneMatches(preview.importableTracks).getOrElse { error ->
                localState.update {
                    it.copy(
                        isImportingPreview = false,
                        previewError = error.message ?: "Could not import this playlist."
                    )
                }
                return@launch
            }
            if (conflicts.isNotEmpty()) {
                localState.update {
                    it.copy(
                        isImportingPreview = false,
                        tombstoneConflict = conflicts.first(),
                        pendingImportCandidates = preview.importableTracks,
                        pendingImportSourceLabel = "Spotify Playlist"
                    )
                }
            } else {
                importPlaylistTracks(preview.importableTracks, preview.playlistTitle, sourceLabel = "Spotify Playlist")
            }
        }
    }

    fun clearSpotifyPreview() {
        localState.update {
            it.copy(
                spotifyPreview = null,
                previewError = null,
                isPreviewLoading = false,
                isImportingPreview = false
            )
        }
    }

    fun importSpotifyPlaylistFile(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            localState.update {
                it.copy(
                    isPreviewLoading = true,
                    previewError = null,
                    spotifyPreview = null
                )
            }

            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }

            if (raw == null) {
                localState.update {
                    it.copy(isPreviewLoading = false, previewError = "Could not read the selected file.")
                }
                return@launch
            }

            spotifyPlaylistJsonImporter.parse(raw)
                .onSuccess { preview ->
                    localState.update {
                        it.copy(
                            isPreviewLoading = false,
                            spotifyPreview = preview,
                            statusMessage = "Previewed ${preview.importableTracks.size} tracks from file."
                        )
                    }
                }
                .onFailure { error ->
                    localState.update {
                        it.copy(
                            isPreviewLoading = false,
                            previewError = error.message ?: "Could not read this playlist file."
                        )
                    }
                }
        }
    }

    fun updateQueueDraftScore(scoreTenths: Int) {
        localState.update { state ->
            val queue = state.ratingQueue ?: return@update state
            state.copy(ratingQueue = queue.copy(draftScoreTenths = scoreTenths, errorMessage = null))
        }
    }

    fun saveQueueScore() {
        val queue = uiState.value.ratingQueue ?: return
        val songId = queue.currentSongId ?: return
        if (queue.isSaving || queue.completion != null) return
        localState.update { state ->
            val currentQueue = state.ratingQueue ?: return@update state
            state.copy(ratingQueue = currentQueue.copy(isSaving = true, errorMessage = null))
        }
        viewModelScope.launch {
            rankingRepository.saveScore(songId, queue.draftScoreTenths)
                .onSuccess {
                    advanceQueue(markRated = true)
                }
                .onFailure {
                    localState.update { state ->
                        val currentQueue = state.ratingQueue ?: return@update state
                        state.copy(
                            ratingQueue = currentQueue.copy(
                                isSaving = false,
                                errorMessage = "Could not save score. Try again."
                            )
                        )
                    }
                }
        }
    }

    fun skipQueueSong() {
        val queue = uiState.value.ratingQueue ?: return
        if (queue.isSaving || queue.completion != null) return
        advanceQueue(markRated = false)
    }

    fun dismissRatingQueue() {
        clearQueuePreview()
        localState.update { it.copy(ratingQueue = null) }
        tryResolveNextPendingRatingQueueLaunch(uiState.value.songs)
    }

    private fun cancelPendingRatingQueueLaunches() {
        pendingRatingQueueLaunches = emptyList()
        pendingRatingQueueLaunchWatcherJob?.cancel()
        pendingRatingQueueLaunchWatcherJob = null
    }

    fun dismissTips() {
        val currentSettings = uiState.value.settings
        if (!currentSettings.showTips) return
        viewModelScope.launch {
            settingsRepository.saveSettings(currentSettings.copy(showTips = false))
        }
    }

    fun toggleQueuePreview() {
        val queue = uiState.value.ratingQueue ?: return
        val song = uiState.value.songs.firstOrNull { it.id == queue.currentSongId } ?: return
        when (queue.previewState) {
            ImportRatingQueuePreviewState.Playing -> {
                songPreviewPlayer.pause()
                localState.update { state ->
                    val currentQueue = state.ratingQueue ?: return@update state
                    state.copy(ratingQueue = currentQueue.copy(previewState = ImportRatingQueuePreviewState.Available))
                }
            }

            ImportRatingQueuePreviewState.Available -> playQueuePreview(song.id, armAutoplay = true)
            ImportRatingQueuePreviewState.Loading -> {
                localState.update { state ->
                    val currentQueue = state.ratingQueue ?: return@update state
                    state.copy(ratingQueue = currentQueue.copy(autoplayArmed = true))
                }
            }

            ImportRatingQueuePreviewState.Unavailable -> resolveQueuePreview(song, armAutoplay = true)
        }
    }

    private suspend fun importSearchCandidate(candidate: MusicTrackCandidate, existingSongIds: Set<String>) {
        importRepository.importTracks(listOf(candidate), "iTunes search")
            .onSuccess { count ->
                localState.update {
                    it.copy(
                        addingTrackIds = it.addingTrackIds - candidate.externalId,
                        addedTrackIds = if (count > 0) it.addedTrackIds + candidate.externalId else it.addedTrackIds,
                        duplicateTrackIds = if (count == 0) it.duplicateTrackIds + candidate.externalId else it.duplicateTrackIds - candidate.externalId,
                        statusMessage = if (count > 0) {
                            "Added ${candidate.title} to your ladder."
                        } else {
                            "${candidate.title} is already in your ladder."
                        }
                    )
                }
                if (count > 0) {
                    scheduleRatingQueueLaunch(
                        kind = ImportRatingQueueKind.SINGLE_SONG,
                        songKeys = listOf(candidate.toSongLookupKey()),
                        existingSongIds = existingSongIds,
                        expectedCount = count
                    )
                }
            }
            .onFailure { error ->
                localState.update {
                    it.copy(
                        addingTrackIds = it.addingTrackIds - candidate.externalId,
                        statusMessage = error.message ?: "Import failed."
                    )
                }
            }
    }

    private suspend fun importPlaylistTracks(
        candidates: List<MusicTrackCandidate>,
        playlistTitle: String,
        sourceLabel: String = "YouTube Music Playlist"
    ) {
        val existingSongIds = currentSongIds()
        importRepository.importTracks(candidates, sourceLabel)
            .onSuccess { count ->
                localState.update {
                    it.copy(
                        isImportingPreview = false,
                        youtubeMusicPreview = null,
                        spotifyPreview = null,
                        previewError = null,
                        statusMessage = "Imported $count songs from $playlistTitle.",
                        youtubeMusicPlaylistUrl = ""
                    )
                }
                scheduleRatingQueueLaunch(
                    kind = ImportRatingQueueKind.PLAYLIST,
                    songKeys = candidates.distinctSongLookupKeys(),
                    existingSongIds = existingSongIds,
                    expectedCount = count
                )
            }
            .onFailure { error ->
                localState.update {
                    it.copy(
                        isImportingPreview = false,
                        previewError = error.message ?: "Could not import this playlist."
                    )
                }
            }
    }

    private fun advanceQueue(markRated: Boolean) {
        clearQueuePreview()
        val songs = uiState.value.songs
        localState.update { state ->
            val queue = state.ratingQueue ?: return@update state
            val nextRatedCount = queue.ratedCount + if (markRated) 1 else 0
            val nextSkippedCount = queue.skippedCount + if (markRated) 0 else 1
            val nextIndex = queue.currentIndex + 1
            val nextQueue = if (nextIndex >= queue.songIds.size) {
                queue.copy(
                    ratedCount = nextRatedCount,
                    skippedCount = nextSkippedCount,
                    isSaving = false,
                    errorMessage = null,
                    completion = ImportRatingQueueCompletion(
                        ratedCount = nextRatedCount,
                        skippedCount = nextSkippedCount
                    ),
                    previewState = ImportRatingQueuePreviewState.Unavailable
                )
            } else {
                val nextSong = songs.firstOrNull { it.id == queue.songIds[nextIndex] }
                queue.copy(
                    currentIndex = nextIndex,
                    draftScoreTenths = nextSong?.scoreTenths ?: 55,
                    ratedCount = nextRatedCount,
                    skippedCount = nextSkippedCount,
                    isSaving = false,
                    errorMessage = null,
                    previewState = ImportRatingQueuePreviewState.Loading
                )
            }
            state.copy(ratingQueue = nextQueue)
        }
        prepareQueuePreviewForCurrentSong()
    }

    private fun scheduleRatingQueueLaunch(
        kind: ImportRatingQueueKind,
        songKeys: List<SongLookupKey>,
        existingSongIds: Set<String>,
        expectedCount: Int
    ) {
        if (expectedCount <= 0 || songKeys.isEmpty()) return
        pendingRatingQueueLaunches = pendingRatingQueueLaunches + PendingRatingQueueLaunch(
            kind = kind,
            songKeys = songKeys,
            existingSongIds = existingSongIds,
            expectedCount = expectedCount
        )
        if (pendingRatingQueueLaunchWatcherJob?.isActive != true) {
            pendingRatingQueueLaunchWatcherJob = viewModelScope.launch {
                songRepository.observeSongs().collectLatest { songs ->
                    tryResolveNextPendingRatingQueueLaunch(songs)
                }
            }
        }
        tryResolveNextPendingRatingQueueLaunch(uiState.value.songs)
    }

    private fun tryResolveNextPendingRatingQueueLaunch(songs: List<Song>) {
        if (localState.value.ratingQueue != null) return
        val pending = pendingRatingQueueLaunches.firstOrNull() ?: run {
            pendingRatingQueueLaunchWatcherJob?.cancel()
            pendingRatingQueueLaunchWatcherJob = null
            return
        }
        val newSongs = songs.filter { it.id !in pending.existingSongIds }
        val matchedSongIds = pending.songKeys.mapNotNull { key ->
            newSongs.firstOrNull { song -> song.matchesLookupKey(key) }?.id
        }
        if (matchedSongIds.size < pending.expectedCount) return
        pendingRatingQueueLaunches = pendingRatingQueueLaunches.drop(1)
        clearQueuePreview()
        localState.update { state ->
            state.copy(
                ratingQueue = ImportRatingQueueState(
                    kind = pending.kind,
                    songIds = matchedSongIds.take(pending.expectedCount),
                    draftScoreTenths = songs.firstOrNull { it.id == matchedSongIds.firstOrNull() }?.scoreTenths ?: 55
                )
            )
        }
        prepareQueuePreviewForCurrentSong()
        if (pendingRatingQueueLaunches.isEmpty()) {
            pendingRatingQueueLaunchWatcherJob?.cancel()
            pendingRatingQueueLaunchWatcherJob = null
        }
    }

    private fun prepareQueuePreviewForCurrentSong() {
        val queue = localState.value.ratingQueue ?: return
        if (queue.completion != null) return
        val song = uiState.value.songs.firstOrNull { it.id == queue.currentSongId } ?: return
        resolveQueuePreview(song, armAutoplay = false)
    }

    private fun resolveQueuePreview(song: Song, armAutoplay: Boolean) {
        val generation = ++queuePreviewGeneration
        queuePreviewJob?.cancel()
        localState.update { state ->
            val queue = state.ratingQueue ?: return@update state
            if (queue.currentSongId != song.id || queue.completion != null) return@update state
            state.copy(
                ratingQueue = queue.copy(
                    autoplayArmed = queue.autoplayArmed || armAutoplay,
                    previewState = ImportRatingQueuePreviewState.Loading
                )
            )
        }
        queuePreviewJob = viewModelScope.launch {
            val previewUrl = songPreviewResolver.resolve(song)
            if (generation != queuePreviewGeneration) return@launch
            if (previewUrl == null) {
                queuePreviewUrls.remove(song.id)
            } else {
                queuePreviewUrls[song.id] = previewUrl
            }
            localState.update { state ->
                val queue = state.ratingQueue ?: return@update state
                if (queue.currentSongId != song.id || queue.completion != null) return@update state
                state.copy(
                    ratingQueue = queue.copy(
                        previewState = if (previewUrl == null) {
                            ImportRatingQueuePreviewState.Unavailable
                        } else {
                            ImportRatingQueuePreviewState.Available
                        }
                    )
                )
            }
            if (
                previewUrl != null &&
                localState.value.ratingQueue?.autoplayArmed == true &&
                uiState.value.settings.autoPlayMatchupPreviews
            ) {
                playQueuePreview(song.id, armAutoplay = false)
            }
        }
    }

    private fun playQueuePreview(songId: String, armAutoplay: Boolean) {
        val previewUrl = queuePreviewUrls[songId] ?: return
        songPreviewPlayer.stop()
        runCatching {
            songPreviewPlayer.play(songId, previewUrl)
        }.onSuccess {
            localState.update { state ->
                val queue = state.ratingQueue ?: return@update state
                if (queue.currentSongId != songId) return@update state
                state.copy(
                    ratingQueue = queue.copy(
                        autoplayArmed = queue.autoplayArmed || armAutoplay,
                        previewState = ImportRatingQueuePreviewState.Playing
                    )
                )
            }
        }.onFailure {
            localState.update { state ->
                val queue = state.ratingQueue ?: return@update state
                if (queue.currentSongId != songId) return@update state
                state.copy(ratingQueue = queue.copy(previewState = ImportRatingQueuePreviewState.Available))
            }
        }
    }

    private fun clearQueuePreview() {
        queuePreviewGeneration += 1
        queuePreviewJob?.cancel()
        queuePreviewJob = null
        songPreviewPlayer.stop()
    }

    private fun handleQueuePreviewEvent(event: SongPreviewPlaybackEvent) {
        localState.update { state ->
            val queue = state.ratingQueue ?: return@update state
            if (queue.currentSongId != event.songId) return@update state
            state.copy(
                ratingQueue = queue.copy(
                    previewState = if (event.failed) {
                        ImportRatingQueuePreviewState.Unavailable
                    } else {
                        ImportRatingQueuePreviewState.Available
                    }
                )
            )
        }
    }

    private fun currentSongIds(): Set<String> =
        uiState.value.songs.mapTo(mutableSetOf()) { it.id }

    private fun importKey(candidate: MusicTrackCandidate): String =
        "${candidate.sourceType.name}:${candidate.externalId}:${candidate.title.trim().lowercase()}::${candidate.artist.trim().lowercase()}"

    private suspend fun performItunesSearch(query: String) {
        localState.update {
            it.copy(
                isSearching = true,
                searchResults = emptyList(),
                statusMessage = "Searching iTunes..."
            )
        }

        try {
            musicSourceClient.searchTracks(query)
                .onSuccess { results ->
                    localState.update {
                        it.copy(
                            searchResults = results,
                            statusMessage = if (results.isEmpty()) {
                                "No songs found for \"$query\"."
                            } else {
                                "Found ${results.size} tracks."
                            }
                        )
                    }
                }
                .onFailure { error ->
                    localState.update {
                        it.copy(statusMessage = error.message ?: "iTunes search failed.")
                    }
                }
        } finally {
            localState.update { it.copy(isSearching = false) }
        }
    }

    private fun scheduleSearchCueReset() {
        clearAddedStateJob?.cancel()
        clearAddedStateJob = viewModelScope.launch {
            delay(2_500)
            localState.update {
                it.copy(
                    addedTrackIds = emptySet(),
                    duplicateTrackIds = emptySet()
                )
            }
        }
    }
}

private fun MusicTrackCandidate.toSongLookupKey(): SongLookupKey = SongLookupKey(
    title = title.trim(),
    artist = artist.trim(),
    sourceTypeName = sourceType.name,
    externalId = externalId
)

private fun List<MusicTrackCandidate>.distinctSongLookupKeys(): List<SongLookupKey> =
    filter { it.title.isNotBlank() && it.artist.isNotBlank() }
        .distinctBy { "${it.title.trim().lowercase()}::${it.artist.trim().lowercase()}" }
        .map { it.toSongLookupKey() }

private fun Song.matchesLookupKey(key: SongLookupKey): Boolean =
    title.trim().equals(key.title, ignoreCase = true) &&
        artist.trim().equals(key.artist, ignoreCase = true) &&
        sourceType.name == key.sourceTypeName &&
        externalId == key.externalId

private data object DefaultLibraryRankingRepository : RankingRepository {
    override fun observeStats() = flowOf(AppStats())

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> = Result.success(Unit)

    override suspend fun recordSkip(songIds: List<String>): Result<Unit> = Result.success(Unit)
}

private data object DefaultLibrarySettingsRepository : SettingsRepository {
    override fun observeSettings() = flowOf(RankingSettings())

    override suspend fun saveSettings(settings: RankingSettings): Result<Unit> = Result.success(Unit)
}
