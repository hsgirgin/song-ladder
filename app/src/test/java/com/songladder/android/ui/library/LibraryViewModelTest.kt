package com.songladder.android.ui.library

import android.content.ContentResolver
import android.net.Uri
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.PlaylistImportPreview
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.repository.ImportRepository
import com.songladder.android.domain.repository.MusicSourceClient
import com.songladder.android.domain.repository.PlaylistSourceClient
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
            ),
            playlistSourceClient = FakePlaylistSourceClient(Result.success(emptyPreview()))
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.updateSearchQuery("Dreams")
        runCurrent()
        advanceTimeBy(401)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.searchResults.size)
        assertEquals("Found 1 tracks.", viewModel.uiState.value.statusMessage)
        assertTrue(!viewModel.uiState.value.isSearching)
    }

    @Test
    fun `short queries do not trigger search`() = runTest {
        val musicSourceClient = FakeMusicSourceClient(emptyList())
        val viewModel = LibraryViewModel(
            songRepository = FakeSongRepository(),
            importRepository = FakeImportRepository(),
            musicSourceClient = musicSourceClient,
            playlistSourceClient = FakePlaylistSourceClient(Result.success(emptyPreview()))
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.updateSearchQuery("a")
        runCurrent()
        advanceTimeBy(401)
        advanceUntilIdle()

        assertEquals(0, musicSourceClient.searchCallCount)
        assertEquals("Keep typing to search iTunes.", viewModel.uiState.value.statusMessage)
    }

    @Test
    fun `add search result uses import repository and reports success`() = runTest {
        val fakeImportRepository = FakeImportRepository()
        val viewModel = LibraryViewModel(
            songRepository = FakeSongRepository(),
            importRepository = fakeImportRepository,
            musicSourceClient = FakeMusicSourceClient(emptyList()),
            playlistSourceClient = FakePlaylistSourceClient(Result.success(emptyPreview()))
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        val candidate = MusicTrackCandidate(
            externalId = "1",
            title = "Nights",
            artist = "Frank Ocean",
            album = "Blonde",
            sourceType = MusicSourceType.ITUNES
        )

        viewModel.addSearchResult(candidate)
        runCurrent()

        assertEquals(1, fakeImportRepository.imported.size)
        assertEquals("Added Nights to your ladder.", viewModel.uiState.value.statusMessage)
        assertTrue("1" in viewModel.uiState.value.addedTrackIds)
    }

    @Test
    fun `preview youtube music playlist updates preview state`() = runTest {
        val preview = PlaylistImportPreview(
            playlistTitle = "Drive Home",
            importableTracks = listOf(
                MusicTrackCandidate(
                    externalId = "ytm-1",
                    title = "Midnight City",
                    artist = "M83",
                    sourceType = MusicSourceType.YOUTUBE_MUSIC
                )
            ),
            ambiguousTracks = emptyList()
        )
        val viewModel = LibraryViewModel(
            songRepository = FakeSongRepository(),
            importRepository = FakeImportRepository(),
            musicSourceClient = FakeMusicSourceClient(emptyList()),
            playlistSourceClient = FakePlaylistSourceClient(Result.success(preview))
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.updateYoutubeMusicPlaylistUrl("https://music.youtube.com/playlist?list=PL123")
        viewModel.previewYoutubeMusicPlaylist()
        advanceUntilIdle()

        assertEquals("Drive Home", viewModel.uiState.value.youtubeMusicPreview?.playlistTitle)
        assertEquals(1, viewModel.uiState.value.youtubeMusicPreview?.importableTracks?.size)
    }

    @Test
    fun `confirm youtube music preview imports ready tracks only`() = runTest {
        val fakeImportRepository = FakeImportRepository()
        val preview = PlaylistImportPreview(
            playlistTitle = "Drive Home",
            importableTracks = listOf(
                MusicTrackCandidate(
                    externalId = "ytm-1",
                    title = "Midnight City",
                    artist = "M83",
                    sourceType = MusicSourceType.YOUTUBE_MUSIC
                )
            ),
            ambiguousTracks = emptyList()
        )
        val viewModel = LibraryViewModel(
            songRepository = FakeSongRepository(),
            importRepository = fakeImportRepository,
            musicSourceClient = FakeMusicSourceClient(emptyList()),
            playlistSourceClient = FakePlaylistSourceClient(Result.success(preview))
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.updateYoutubeMusicPlaylistUrl("https://music.youtube.com/playlist?list=PL123")
        viewModel.previewYoutubeMusicPlaylist()
        advanceUntilIdle()
        viewModel.confirmYoutubeMusicPreviewImport()
        advanceUntilIdle()

        assertEquals(1, fakeImportRepository.imported.size)
        assertEquals(MusicSourceType.YOUTUBE_MUSIC, fakeImportRepository.imported.single().sourceType)
        assertEquals("", viewModel.uiState.value.youtubeMusicPlaylistUrl)
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
    var searchCallCount: Int = 0

    override suspend fun searchTracks(query: String): Result<List<MusicTrackCandidate>> {
        searchCallCount += 1
        return Result.success(results)
    }
}

private class FakePlaylistSourceClient(
    private val result: Result<PlaylistImportPreview>
) : PlaylistSourceClient {
    override suspend fun previewPlaylist(url: String): Result<PlaylistImportPreview> = result
}

private fun emptyPreview(): PlaylistImportPreview = PlaylistImportPreview(
    playlistTitle = "Empty",
    importableTracks = emptyList(),
    ambiguousTracks = emptyList()
)
