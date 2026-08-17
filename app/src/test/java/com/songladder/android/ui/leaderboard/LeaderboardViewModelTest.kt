package com.songladder.android.ui.leaderboard

import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LeaderboardViewModelTest {
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
    fun `top rated is the default sort`() = runTest {
        val viewModel = LeaderboardViewModel(
            songRepository = FakeLeaderboardSongRepository(
                listOf(
                    leaderboardSong(id = "1", rating = 1100, wins = 2, losses = 1, skips = 0),
                    leaderboardSong(id = "2", rating = 1300, wins = 1, losses = 0, skips = 4)
                )
            )
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()

        assertEquals(LeaderboardSortMode.TOP_RATED, viewModel.uiState.value.sortMode)
        assertEquals("2", viewModel.uiState.value.songs.first().id)
    }

    @Test
    fun `most skipped sort prioritizes skip count`() = runTest {
        val viewModel = LeaderboardViewModel(
            songRepository = FakeLeaderboardSongRepository(
                listOf(
                    leaderboardSong(id = "1", rating = 1400, wins = 4, losses = 1, skips = 1),
                    leaderboardSong(id = "2", rating = 1200, wins = 1, losses = 1, skips = 5)
                )
            )
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.updateSortMode(LeaderboardSortMode.MOST_SKIPPED)
        advanceUntilIdle()

        assertEquals("2", viewModel.uiState.value.songs.first().id)
    }

    @Test
    fun `top rated keeps score first even when Elo rating is lower`() = runTest {
        val viewModel = LeaderboardViewModel(
            songRepository = FakeLeaderboardSongRepository(
                listOf(
                    leaderboardSong(id = "higher-score", rating = 1100, wins = 0, losses = 0, skips = 0, scoreTenths = 80),
                    leaderboardSong(id = "higher-elo", rating = 1300, wins = 0, losses = 0, skips = 0, scoreTenths = 70)
                )
            )
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()

        assertEquals("higher-score", viewModel.uiState.value.songs.first().id)
    }
}

private class FakeLeaderboardSongRepository(
    songs: List<Song>
) : SongRepository {
    private val songFlow = MutableStateFlow(songs)

    override fun observeSongs(): Flow<List<Song>> = songFlow

    override suspend fun addSong(input: SongInput): Result<Unit> = Result.success(Unit)

    override suspend fun removeSong(songId: String): Result<Unit> = Result.success(Unit)

    override suspend fun resetLibrary(): Result<Unit> = Result.success(Unit)
}

private fun leaderboardSong(
    id: String,
    rating: Int,
    wins: Int,
    losses: Int,
    skips: Int,
    scoreTenths: Int? = null
): Song {
    return Song(
        id = id,
        title = "Song $id",
        artist = "Artist $id",
        createdAt = 1L,
        scoreTenths = scoreTenths,
        elo = rating.toDouble(),
        rating = rating,
        wins = wins,
        losses = losses,
        skips = skips
    )
}
