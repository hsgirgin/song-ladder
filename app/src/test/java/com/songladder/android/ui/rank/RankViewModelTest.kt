package com.songladder.android.ui.rank

import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RankViewModelTest {
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
    fun `ui state reports not ready when fewer than two songs exist`() = runTest {
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(listOf(fakeSong(id = "1", title = "One"))),
            rankingRepository = FakeRankingRepository()
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isReady)
        assertEquals("Add at least two songs to start ranking.", viewModel.uiState.value.message)
    }

    @Test
    fun `rank winner updates streak and clears transient feedback`() = runTest {
        val songs = listOf(
            fakeSong(id = "1", title = "Dreams"),
            fakeSong(id = "2", title = "Go Your Own Way")
        )
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = FakeRankingRepository()
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()
        viewModel.rankWinner("1", "2")
        runCurrent()

        assertEquals("Picked Dreams", viewModel.uiState.value.sessionFeedback)
        assertEquals(1, viewModel.uiState.value.streakCount)

        advanceTimeBy(1_501)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.sessionFeedback)
    }

    @Test
    fun `skip resets streak`() = runTest {
        val songs = listOf(
            fakeSong(id = "1", title = "Dreams"),
            fakeSong(id = "2", title = "Go Your Own Way")
        )
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = FakeRankingRepository()
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()
        viewModel.rankWinner("1", "2")
        runCurrent()
        viewModel.skip()
        runCurrent()

        assertEquals(0, viewModel.uiState.value.streakCount)
        assertEquals("Matchup skipped", viewModel.uiState.value.sessionFeedback)
    }
}

private class FakeRankSongRepository(
    songs: List<Song>
) : SongRepository {
    private val songFlow = MutableStateFlow(songs)

    override fun observeSongs(): Flow<List<Song>> = songFlow

    override suspend fun addSong(input: com.songladder.android.domain.model.SongInput): Result<Unit> = Result.success(Unit)

    override suspend fun removeSong(songId: String) = Unit

    override suspend fun resetLibrary() = Unit
}

private class FakeRankingRepository : RankingRepository {
    private val stats = MutableStateFlow(AppStats())

    override fun observeStats(): Flow<AppStats> = stats

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> {
        stats.value = stats.value.copy(matchCount = stats.value.matchCount + 1)
        return Result.success(Unit)
    }

    override suspend fun recordSkip(songId: String) {
        stats.value = stats.value.copy(skipCount = stats.value.skipCount + 1)
    }
}

private fun fakeSong(id: String, title: String): Song {
    return Song(
        id = id,
        title = title,
        artist = "Artist $id",
        createdAt = 1L
    )
}
