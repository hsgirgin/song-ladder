package com.songladder.android.ui.rankings

import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.DeletedRankingHistory
import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.RankingHistoryDeletionResult
import com.songladder.android.domain.model.RankingPresentation
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.ScoreSaveResult
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SettingsRepository
import com.songladder.android.domain.repository.SongPreviewPlaybackEvent
import com.songladder.android.domain.repository.SongPreviewPlayer
import com.songladder.android.domain.repository.SongPreviewResolver
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RankingsViewModelTest {
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
    fun `songs tab separates ranked score-first songs from collapsed unrated songs`() = runTest {
        val viewModel = viewModel(
            songs = listOf(
                rankingsSong(id = "unrated-new", scoreTenths = null, createdAt = 4L),
                rankingsSong(id = "score-seven", scoreTenths = 70, elo = 1800.0, createdAt = 3L),
                rankingsSong(id = "score-eight", scoreTenths = 80, elo = 1100.0, createdAt = 2L),
                rankingsSong(id = "unrated-old", scoreTenths = null, createdAt = 1L)
            )
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()

        assertEquals(listOf("score-eight", "score-seven"), viewModel.uiState.value.rankedSongs.map { it.song.id })
        assertEquals(listOf("unrated-new", "unrated-old"), viewModel.uiState.value.unratedSongs.map { it.id })
        assertEquals(false, viewModel.uiState.value.unratedExpanded)
    }

    @Test
    fun `unrated section starts expanded when there are no rated songs`() = runTest {
        val viewModel = viewModel(
            songs = listOf(
                rankingsSong(id = "unrated-old", scoreTenths = null, createdAt = 1L),
                rankingsSong(id = "unrated-new", scoreTenths = null, createdAt = 2L)
            )
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.unratedExpanded)
        assertEquals(listOf("unrated-new", "unrated-old"), viewModel.uiState.value.unratedSongs.map { it.id })
    }

    @Test
    fun `songs search filters titles artists and albums and closing search clears query`() = runTest {
        val viewModel = viewModel(
            songs = listOf(
                rankingsSong(id = "one", title = "First", artist = "Artist", album = "Album"),
                rankingsSong(id = "two", title = "Second", artist = "Needle", album = "Other")
            )
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.setSearchActive(true)
        viewModel.updateSearchQuery("needle")
        advanceUntilIdle()

        assertEquals(listOf("two"), viewModel.uiState.value.rankedSongs.map { it.song.id })

        viewModel.setSearchActive(false)
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.searchQuery)
        assertEquals(2, viewModel.uiState.value.rankedSongs.size)
    }

    @Test
    fun `selecting future tabs closes songs search and reports coming soon`() = runTest {
        val viewModel = viewModel(
            songs = listOf(
                rankingsSong(id = "one", title = "Needle"),
                rankingsSong(id = "two", title = "Other")
            )
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.setSearchActive(true)
        viewModel.updateSearchQuery("needle")
        advanceUntilIdle()

        viewModel.selectTab(RankingsTab.ALBUMS)
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.searchActive)
        assertEquals("", viewModel.uiState.value.searchQuery)
        assertEquals(RankingsTab.ALBUMS, viewModel.uiState.value.selectedTab)
        assertEquals(RankingsStatus.ComingSoon, viewModel.uiState.value.status)

        viewModel.selectTab(RankingsTab.SONGS)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.rankedSongs.size)
    }

    @Test
    fun `presentation changes persist while preserving other settings`() = runTest {
        val settingsRepository = FakeSettingsRepository(
            RankingSettings(
                autoPlayMatchupPreviews = false,
                showTips = false,
                presentation = RankingPresentation.GRID
            )
        )
        val viewModel = viewModel(settingsRepository = settingsRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setPresentation(RankingPresentation.LIST)
        advanceUntilIdle()

        assertEquals(
            RankingSettings(
                autoPlayMatchupPreviews = false,
                showTips = false,
                presentation = RankingPresentation.LIST
            ),
            settingsRepository.settings.value
        )
    }

    @Test
    fun `dismissing rankings tip persists tips off while preserving other settings`() = runTest {
        val settingsRepository = FakeSettingsRepository(
            RankingSettings(
                autoPlayMatchupPreviews = false,
                showTips = true,
                presentation = RankingPresentation.LIST
            )
        )
        val viewModel = viewModel(settingsRepository = settingsRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.dismissRankingsTip()
        advanceUntilIdle()

        assertEquals(
            RankingSettings(
                autoPlayMatchupPreviews = false,
                showTips = false,
                presentation = RankingPresentation.LIST
            ),
            settingsRepository.settings.value
        )
    }

    @Test
    fun `saving a score reports success and delegates to ranking repository`() = runTest {
        val rankingRepository = FakeRankingRepository()
        val viewModel = viewModel(rankingRepository = rankingRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.saveScore("song-1", 85)
        advanceUntilIdle()

        assertEquals("song-1" to 85, rankingRepository.savedScores.single())
        assertTrue(viewModel.uiState.value.status is RankingsStatus.ScoreSaved)
    }

    @Test
    fun `preview tap exposes unavailable state when no preview can be resolved`() = runTest {
        val viewModel = viewModel(
            songs = listOf(rankingsSong(id = "song-1")),
            previewResolver = object : SongPreviewResolver {
                override suspend fun resolve(song: Song): String? = null
            }
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.togglePreview("song-1")
        advanceUntilIdle()

        assertEquals(RankingsPreviewState.Unavailable, viewModel.uiState.value.previews["song-1"])
    }

    @Test
    fun `deleting a song stores undo state and undo restores the ranking subject`() = runTest {
        val songRepository = FakeRankingsSongRepository(listOf(rankingsSong(id = "song-1")))
        val viewModel = RankingsViewModel(
            songRepository = songRepository,
            rankingRepository = FakeRankingRepository(),
            settingsRepository = FakeSettingsRepository(),
            songPreviewResolver = object : SongPreviewResolver {
                override suspend fun resolve(song: Song): String? = null
            },
            songPreviewPlayer = FakeSongPreviewPlayer()
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteSong(viewModel.uiState.value.allSongs.single())
        runCurrent()

        assertEquals(listOf("song-1"), songRepository.removedSongIds)
        assertTrue(viewModel.uiState.value.status is RankingsStatus.DeletedSong)

        viewModel.undoDelete()
        runCurrent()

        assertEquals(listOf("song-1"), songRepository.restoredSubjectIds)
        assertEquals(RankingsStatus.None, viewModel.uiState.value.status)
    }
}

private fun viewModel(
    songs: List<Song> = listOf(rankingsSong(id = "song-1")),
    rankingRepository: FakeRankingRepository = FakeRankingRepository(),
    settingsRepository: FakeSettingsRepository = FakeSettingsRepository(),
    previewResolver: SongPreviewResolver = object : SongPreviewResolver {
        override suspend fun resolve(song: Song): String? = "https://example.test/${song.id}.mp3"
    },
    previewPlayer: SongPreviewPlayer = FakeSongPreviewPlayer()
): RankingsViewModel {
    return RankingsViewModel(
        songRepository = FakeRankingsSongRepository(songs),
        rankingRepository = rankingRepository,
        settingsRepository = settingsRepository,
        songPreviewResolver = previewResolver,
        songPreviewPlayer = previewPlayer
    )
}

private class FakeRankingsSongRepository(
    songs: List<Song>
) : SongRepository {
    private val songFlow = MutableStateFlow(songs)
    val removedSongIds = mutableListOf<String>()
    val restoredSubjectIds = mutableListOf<String>()

    override fun observeSongs(): Flow<List<Song>> = songFlow

    override suspend fun addSong(input: SongInput): Result<Unit> = Result.success(Unit)

    override suspend fun removeSong(songId: String): Result<Unit> {
        removedSongIds += songId
        return Result.success(Unit)
    }

    override suspend fun resetLibrary(): Result<Unit> = Result.success(Unit)

    override suspend fun restoreSong(input: SongInput, rankingSubjectId: String): Result<Unit> {
        restoredSubjectIds += rankingSubjectId
        return Result.success(Unit)
    }
}

private class FakeRankingRepository : RankingRepository {
    val savedScores = mutableListOf<Pair<String, Int>>()

    override fun observeStats(): Flow<AppStats> = flowOf(AppStats())

    override fun observeMatchupEvents(): Flow<List<MatchupEvent>> = flowOf(emptyList())

    override fun observeDeletedRankingHistories(): Flow<List<DeletedRankingHistory>> = flowOf(emptyList())

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> = Result.success(Unit)

    override suspend fun recordSkip(songIds: List<String>): Result<Unit> = Result.success(Unit)

    override suspend fun saveScore(songId: String, scoreTenths: Int): Result<ScoreSaveResult> {
        savedScores += songId to scoreTenths
        return Result.success(ScoreSaveResult(songId = songId, scoreTenths = scoreTenths))
    }

    override suspend fun deleteRankingHistory(rankingSubjectId: String): Result<RankingHistoryDeletionResult> =
        Result.success(RankingHistoryDeletionResult(rankingSubjectId, deletedEventCount = 0))
}

private class FakeSettingsRepository(
    initialSettings: RankingSettings = RankingSettings()
) : SettingsRepository {
    val settings = MutableStateFlow(initialSettings)

    override fun observeSettings(): Flow<RankingSettings> = settings

    override suspend fun saveSettings(settings: RankingSettings): Result<Unit> {
        this.settings.value = settings
        return Result.success(Unit)
    }
}

private class FakeSongPreviewPlayer : SongPreviewPlayer {
    override val events = MutableSharedFlow<SongPreviewPlaybackEvent>()
    override fun play(songId: String, url: String) = Unit
    override fun pause() = Unit
    override fun stop() = Unit
}

private fun rankingsSong(
    id: String,
    title: String = "Song $id",
    artist: String = "Artist $id",
    album: String = "",
    createdAt: Long = 1L,
    scoreTenths: Int? = 80,
    elo: Double = 1200.0,
    wins: Int = 0,
    losses: Int = 0,
    skips: Int = 0
): Song {
    return Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        createdAt = createdAt,
        scoreTenths = scoreTenths,
        elo = elo,
        wins = wins,
        losses = losses,
        skips = skips
    )
}
