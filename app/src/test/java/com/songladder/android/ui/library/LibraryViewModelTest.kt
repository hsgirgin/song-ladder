package com.songladder.android.ui.library

import android.content.ContentResolver
import android.net.Uri
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.repository.ImportRepository
import com.songladder.android.domain.repository.MusicSourceClient
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `search updates results and status message`() = runTest {
        val fakeSongRepository = FakeSongRepository()
        val viewModel = LibraryViewModel(
            songRepository = fakeSongRepository,
            importRepository = FakeImportRepository(),
            musicSourceClient = FakeMusicSourceClient(
                listOf(
                    MusicTrackCandidate(
                        externalId = "1",
                        title = "Dreams",
                        artist = "Fleetwood Mac",
                        album = "Rumours",
                        sourceType = MusicSourceType.ITUNES
                    )
                )
            )
        )

        viewModel.updateSearchQuery("Dreams")
        viewModel.searchItunes()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.searchResults.size)
        assertEquals("Found 1 tracks.", viewModel.uiState.value.statusMessage)
        assertTrue(!viewModel.uiState.value.isSearching)
    }

    @Test
    fun `add search result uses import repository and reports success`() = runTest {
        val fakeImportRepository = FakeImportRepository()
        val viewModel = LibraryViewModel(
            songRepository = FakeSongRepository(),
            importRepository = fakeImportRepository,
            musicSourceClient = FakeMusicSourceClient(emptyList())
        )
        val candidate = MusicTrackCandidate(
            externalId = "1",
            title = "Nights",
            artist = "Frank Ocean",
            album = "Blonde",
            sourceType = MusicSourceType.ITUNES
        )

        viewModel.addSearchResult(candidate)
        advanceUntilIdle()

        assertEquals(1, fakeImportRepository.imported.size)
        assertEquals("Added Nights to your ladder.", viewModel.uiState.value.statusMessage)
    }
}

private class FakeSongRepository : SongRepository {
    private val songs = MutableStateFlow<List<Song>>(emptyList())

    override fun observeSongs(): Flow<List<Song>> = songs

    override suspend fun addSong(input: SongInput): Result<Unit> = Result.success(Unit)

    override suspend fun removeSong(songId: String) = Unit

    override suspend fun resetLibrary() = Unit
}

private class FakeImportRepository : ImportRepository {
    val imported = mutableListOf<MusicTrackCandidate>()

    override suspend fun seedSampleSongs() = Unit

    override suspend fun importTracks(candidates: List<MusicTrackCandidate>, sourceLabel: String): Result<Int> {
        imported += candidates
        return Result.success(candidates.size)
    }

    override suspend fun importFromJson(contentResolver: ContentResolver, uri: Uri): Result<Int> = Result.success(0)

    override suspend fun exportToJson(contentResolver: ContentResolver, uri: Uri): Result<Unit> = Result.success(Unit)
}

private class FakeMusicSourceClient(
    private val results: List<MusicTrackCandidate>
) : MusicSourceClient {
    override suspend fun searchTracks(query: String): Result<List<MusicTrackCandidate>> = Result.success(results)
}
