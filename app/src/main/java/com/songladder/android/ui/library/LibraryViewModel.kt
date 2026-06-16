package com.songladder.android.ui.library

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.repository.ImportRepository
import com.songladder.android.domain.repository.MusicSourceClient
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

data class LibraryUiState(
    val songs: List<Song> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<MusicTrackCandidate> = emptyList(),
    val statusMessage: String = "",
    val isSearching: Boolean = false,
    val addingTrackIds: Set<String> = emptySet(),
    val addedTrackIds: Set<String> = emptySet(),
    val duplicateTrackIds: Set<String> = emptySet()
)

@OptIn(FlowPreview::class)
class LibraryViewModel(
    private val songRepository: SongRepository,
    private val importRepository: ImportRepository,
    private val musicSourceClient: MusicSourceClient
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
                                statusMessage = "Keep typing to search iTunes."
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
                    mutableState.update { it.copy(statusMessage = "Song added to your ladder.") }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(statusMessage = error.message ?: "Could not add song.") }
                }
        }
    }

    fun removeSong(songId: String) {
        viewModelScope.launch {
            songRepository.removeSong(songId)
            mutableState.update { it.copy(statusMessage = "Song removed.") }
        }
    }

    fun resetLibrary() {
        viewModelScope.launch {
            songRepository.resetLibrary()
            mutableState.update { it.copy(statusMessage = "Library reset.") }
        }
    }

    fun seedSampleSongs() {
        viewModelScope.launch {
            importRepository.seedSampleSongs()
            mutableState.update { it.copy(statusMessage = "Sample pack imported.") }
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
                    statusMessage = "Adding ${candidate.title}..."
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
                                "Added ${candidate.title} to your ladder."
                            } else {
                                "${candidate.title} is already in your ladder."
                            }
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            addingTrackIds = it.addingTrackIds - candidate.externalId,
                            statusMessage = error.message ?: "Import failed."
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
                    mutableState.update { it.copy(statusMessage = "Imported $count songs from JSON.") }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(statusMessage = error.message ?: "JSON import failed.") }
                }
        }
    }

    fun exportJson(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            importRepository.exportToJson(contentResolver, uri)
                .onSuccess {
                    mutableState.update { it.copy(statusMessage = "Exported your library.") }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(statusMessage = error.message ?: "Export failed.") }
                }
        }
    }

    private suspend fun performItunesSearch(query: String) {
        mutableState.update {
            it.copy(
                isSearching = true,
                searchResults = emptyList(),
                statusMessage = "Searching iTunes..."
            )
        }

        try {
            musicSourceClient.searchTracks(query)
                .onSuccess { results ->
                    mutableState.update {
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
                    mutableState.update {
                        it.copy(
                            statusMessage = error.message ?: "iTunes search failed."
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
