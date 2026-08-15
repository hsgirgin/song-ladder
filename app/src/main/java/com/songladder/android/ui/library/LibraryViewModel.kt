package com.songladder.android.ui.library

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.domain.model.AmbiguousPlaylistTrack
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.PlaylistImportPreview
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.repository.ImportRepository
import com.songladder.android.domain.repository.MusicSourceClient
import com.songladder.android.domain.repository.PlaylistSourceClient
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryMessageType {
    KEEP_TYPING,
    SONG_ADDED,
    SONG_REMOVED,
    LIBRARY_RESET,
    SAMPLE_IMPORTED,
    SAMPLE_ALREADY_IMPORTED,
    ADDING_TRACK,
    TRACK_ADDED,
    TRACK_DUPLICATE,
    JSON_IMPORTED,
    PLAYLIST_IMPORTED,
    JSON_EXPORTED,
    PREVIEWED_PLAYLIST,
    SEARCHING,
    SEARCH_EMPTY,
    SEARCH_RESULTS,
    ADD_FAILED,
    REMOVE_FAILED,
    RESET_FAILED,
    SAMPLE_IMPORT_FAILED,
    IMPORT_FAILED,
    JSON_IMPORT_FAILED,
    EXPORT_FAILED,
    PLAYLIST_URL_REQUIRED,
    PLAYLIST_PREVIEW_FAILED,
    PLAYLIST_IMPORT_FAILED,
    SEARCH_FAILED
}

data class LibraryMessage(
    val type: LibraryMessageType,
    val args: List<Any> = emptyList()
)

data class LibraryUiState(
    val songs: List<Song> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<MusicTrackCandidate> = emptyList(),
    val statusMessage: LibraryMessage? = null,
    val isSearching: Boolean = false,
    val addingTrackIds: Set<String> = emptySet(),
    val addedTrackIds: Set<String> = emptySet(),
    val duplicateTrackIds: Set<String> = emptySet(),
    val youtubeMusicPlaylistUrl: String = "",
    val isPreviewLoading: Boolean = false,
    val youtubeMusicPreview: PlaylistImportPreview? = null,
    val previewError: LibraryMessage? = null,
    val isImportingPreview: Boolean = false
)

@OptIn(FlowPreview::class)
class LibraryViewModel(
    private val songRepository: SongRepository,
    private val importRepository: ImportRepository,
    private val musicSourceClient: MusicSourceClient,
    private val playlistSourceClient: PlaylistSourceClient
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryUiState())
    private var clearAddedStateJob: Job? = null

    val uiState: StateFlow<LibraryUiState> = combine(
        songRepository.observeSongs(),
        mutableState
    ) { songs, state ->
        state.copy(songs = songs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        viewModelScope.launch {
            mutableState
                .map { it.searchQuery.trim() }
                .distinctUntilChanged()
                .debounce(400)
                .collectLatest { query ->
                    mutableState.update {
                        it.copy(
                            addedTrackIds = emptySet(),
                            duplicateTrackIds = emptySet()
                        )
                    }

                    if (query.isBlank()) {
                        mutableState.update {
                            it.copy(
                                searchResults = emptyList(),
                                isSearching = false
                            )
                        }
                        return@collectLatest
                    }

                    if (query.length < 2) {
                        mutableState.update {
                            it.copy(
                                searchResults = emptyList(),
                                isSearching = false,
                                statusMessage = LibraryMessage(LibraryMessageType.KEEP_TYPING)
                            )
                        }
                        return@collectLatest
                    }

                    performItunesSearch(query)
                }
        }
    }

    fun addSong(title: String, artist: String, album: String) {
        viewModelScope.launch {
            songRepository.addSong(SongInput(title = title, artist = artist, album = album))
                .onSuccess {
                    mutableState.update { it.copy(statusMessage = LibraryMessage(LibraryMessageType.SONG_ADDED)) }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(statusMessage = LibraryMessage(LibraryMessageType.ADD_FAILED)) }
                }
        }
    }

    fun removeSong(songId: String) {
        viewModelScope.launch {
            songRepository.removeSong(songId)
                .onSuccess {
                    mutableState.update { it.copy(statusMessage = LibraryMessage(LibraryMessageType.SONG_REMOVED)) }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(statusMessage = LibraryMessage(LibraryMessageType.REMOVE_FAILED)) }
                }
        }
    }

    fun resetLibrary() {
        viewModelScope.launch {
            songRepository.resetLibrary()
                .onSuccess {
                    mutableState.update { it.copy(statusMessage = LibraryMessage(LibraryMessageType.LIBRARY_RESET)) }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(statusMessage = LibraryMessage(LibraryMessageType.RESET_FAILED)) }
                }
        }
    }

    fun seedSampleSongs() {
        viewModelScope.launch {
            importRepository.seedSampleSongs()
                .onSuccess { count ->
                    mutableState.update {
                        it.copy(
                            statusMessage = if (count > 0) {
                                LibraryMessage(LibraryMessageType.SAMPLE_IMPORTED)
                            } else {
                                LibraryMessage(LibraryMessageType.SAMPLE_ALREADY_IMPORTED)
                            }
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(statusMessage = LibraryMessage(LibraryMessageType.SAMPLE_IMPORT_FAILED)) }
                }
        }
    }

    fun updateSearchQuery(query: String) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun addSearchResult(candidate: MusicTrackCandidate) {
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    addingTrackIds = it.addingTrackIds + candidate.externalId,
                    statusMessage = LibraryMessage(LibraryMessageType.ADDING_TRACK, listOf(candidate.title))
                )
            }
            importRepository.importTracks(listOf(candidate), "iTunes search")
                .onSuccess { count ->
                    mutableState.update {
                        it.copy(
                            addingTrackIds = it.addingTrackIds - candidate.externalId,
                            addedTrackIds = if (count > 0) it.addedTrackIds + candidate.externalId else it.addedTrackIds,
                            duplicateTrackIds = if (count == 0) it.duplicateTrackIds + candidate.externalId else it.duplicateTrackIds - candidate.externalId,
                            statusMessage = if (count > 0) {
                                LibraryMessage(LibraryMessageType.TRACK_ADDED, listOf(candidate.title))
                            } else {
                                LibraryMessage(LibraryMessageType.TRACK_DUPLICATE, listOf(candidate.title))
                            }
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            addingTrackIds = it.addingTrackIds - candidate.externalId,
                            statusMessage = LibraryMessage(LibraryMessageType.IMPORT_FAILED)
                        )
                    }
                }

            scheduleSearchCueReset()
        }
    }

    fun importJson(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            importRepository.importFromJson(contentResolver, uri)
                .onSuccess { count ->
                    mutableState.update { it.copy(statusMessage = LibraryMessage(LibraryMessageType.JSON_IMPORTED, listOf(count))) }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(statusMessage = LibraryMessage(LibraryMessageType.JSON_IMPORT_FAILED)) }
                }
        }
    }

    fun exportJson(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            importRepository.exportToJson(contentResolver, uri)
                .onSuccess {
                    mutableState.update { it.copy(statusMessage = LibraryMessage(LibraryMessageType.JSON_EXPORTED)) }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(statusMessage = LibraryMessage(LibraryMessageType.EXPORT_FAILED)) }
                }
        }
    }

    fun updateYoutubeMusicPlaylistUrl(url: String) {
        mutableState.update {
            it.copy(
                youtubeMusicPlaylistUrl = url,
                previewError = null,
                youtubeMusicPreview = null
            )
        }
    }

    fun previewYoutubeMusicPlaylist() {
        viewModelScope.launch {
            val playlistUrl = mutableState.value.youtubeMusicPlaylistUrl.trim()
            if (playlistUrl.isBlank()) {
                mutableState.update { it.copy(previewError = LibraryMessage(LibraryMessageType.PLAYLIST_URL_REQUIRED)) }
                return@launch
            }

            mutableState.update {
                it.copy(
                    isPreviewLoading = true,
                    previewError = null,
                    youtubeMusicPreview = null
                )
            }

            playlistSourceClient.previewPlaylist(playlistUrl)
                .onSuccess { preview ->
                    mutableState.update {
                        it.copy(
                            isPreviewLoading = false,
                            youtubeMusicPreview = preview,
                            statusMessage = LibraryMessage(LibraryMessageType.PREVIEWED_PLAYLIST, listOf(preview.importableTracks.size))
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isPreviewLoading = false,
                            previewError = LibraryMessage(LibraryMessageType.PLAYLIST_PREVIEW_FAILED)
                        )
                    }
                }
        }
    }

    fun confirmYoutubeMusicPreviewImport() {
        viewModelScope.launch {
            val preview = mutableState.value.youtubeMusicPreview ?: return@launch
            mutableState.update { it.copy(isImportingPreview = true, previewError = null) }

            importRepository.importTracks(preview.importableTracks, "YouTube Music Playlist")
                .onSuccess { count ->
                    mutableState.update {
                        it.copy(
                            isImportingPreview = false,
                            youtubeMusicPreview = null,
                            previewError = null,
                            statusMessage = LibraryMessage(LibraryMessageType.PLAYLIST_IMPORTED, listOf(count)),
                            youtubeMusicPlaylistUrl = ""
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isImportingPreview = false,
                            previewError = LibraryMessage(LibraryMessageType.PLAYLIST_IMPORT_FAILED)
                        )
                    }
                }
        }
    }

    fun clearYoutubeMusicPreview() {
        mutableState.update {
            it.copy(
                youtubeMusicPreview = null,
                previewError = null,
                isPreviewLoading = false,
                isImportingPreview = false
            )
        }
    }

    private suspend fun performItunesSearch(query: String) {
        mutableState.update {
            it.copy(
                isSearching = true,
                searchResults = emptyList(),
                statusMessage = LibraryMessage(LibraryMessageType.SEARCHING)
            )
        }

        try {
            musicSourceClient.searchTracks(query)
                .onSuccess { results ->
                    mutableState.update {
                        it.copy(
                            searchResults = results,
                            statusMessage = if (results.isEmpty()) {
                                LibraryMessage(LibraryMessageType.SEARCH_EMPTY, listOf(query))
                            } else {
                                LibraryMessage(LibraryMessageType.SEARCH_RESULTS, listOf(results.size))
                            }
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            statusMessage = LibraryMessage(LibraryMessageType.SEARCH_FAILED)
                        )
                    }
                }
        } finally {
            mutableState.update { it.copy(isSearching = false) }
        }
    }

    private fun scheduleSearchCueReset() {
        clearAddedStateJob?.cancel()
        clearAddedStateJob = viewModelScope.launch {
            delay(2_500)
            mutableState.update {
                it.copy(
                    addedTrackIds = emptySet(),
                    duplicateTrackIds = emptySet()
                )
            }
        }
    }
}
