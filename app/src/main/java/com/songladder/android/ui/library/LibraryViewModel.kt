package com.songladder.android.ui.library

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.songladder.android.data.preferences.SessionPreferencesRepository
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.repository.ImportRepository
import com.songladder.android.domain.repository.MusicSourceClient
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val songs: List<Song> = emptyList(),
    val spotifyToken: String = "",
    val spotifyQuery: String = "",
    val spotifyResults: List<MusicTrackCandidate> = emptyList(),
    val statusMessage: String = "",
    val isLoading: Boolean = false
)

class LibraryViewModel(
    private val songRepository: SongRepository,
    private val importRepository: ImportRepository,
    private val musicSourceClient: MusicSourceClient,
    private val sessionPreferencesRepository: SessionPreferencesRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryUiState())

    val uiState: StateFlow<LibraryUiState> = combine(
        songRepository.observeSongs(),
        sessionPreferencesRepository.spotifyToken,
        mutableState
    ) { songs, token, state ->
        state.copy(songs = songs, spotifyToken = token)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

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

    fun saveSpotifyToken(token: String) {
        viewModelScope.launch {
            sessionPreferencesRepository.saveSpotifyToken(token)
            mutableState.update { it.copy(statusMessage = "Spotify token saved.") }
        }
    }

    fun updateSpotifyQuery(query: String) {
        mutableState.update { it.copy(spotifyQuery = query) }
    }

    fun searchSpotify(tokenOverride: String? = null) {
        viewModelScope.launch {
            val query = uiState.value.spotifyQuery
            val token = tokenOverride ?: uiState.value.spotifyToken
            mutableState.update { it.copy(isLoading = true, statusMessage = "Searching Spotify...") }
            musicSourceClient.searchTracks(query, token)
                .onSuccess { results ->
                    mutableState.update {
                        it.copy(
                            spotifyResults = results,
                            isLoading = false,
                            statusMessage = "Found ${results.size} tracks."
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = error.message ?: "Spotify search failed."
                        )
                    }
                }
        }
    }

    fun importSpotifySelection(selection: List<MusicTrackCandidate>) {
        viewModelScope.launch {
            importRepository.importTracks(selection, "Spotify import")
                .onSuccess { count ->
                    mutableState.update { it.copy(statusMessage = "Imported $count tracks from Spotify.") }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(statusMessage = error.message ?: "Import failed.") }
                }
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
}
