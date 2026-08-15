package com.songladder.android.ui.rank

import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SongPreviewPlayer
import com.songladder.android.domain.repository.SongPreviewResolver
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `playback failure makes the preview unavailable`() = runTest {
        val songs = listOf(fakeSong("1", "Dreams"), fakeSong("2", "Go Your Own Way"))
        val player = FakeSongPreviewPlayer()
        val viewModel = RankViewModel(
            FakeRankSongRepository(songs),
            FakeRankingRepository(),
            FakeSongPreviewResolver(mapOf("1" to "https://audio/1.m4a", "2" to null)),
            player
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updatePreviewPrefetch(viewModel.uiState.value.matchup)
        advanceUntilIdle()

        viewModel.togglePreview("1")
        runCurrent()
        player.fail("1")
        runCurrent()

        assertEquals(SongPreviewState.Unavailable, viewModel.uiState.value.previews["1"])
    }

    @Test
    fun `preview controls switch playback and ranking stops it`() = runTest {
        val songs = listOf(
            fakeSong(id = "1", title = "Dreams"),
            fakeSong(id = "2", title = "Go Your Own Way")
        )
        val player = FakeSongPreviewPlayer()
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = FakeRankingRepository(),
            songPreviewResolver = FakeSongPreviewResolver(
                mapOf("1" to "https://audio/1.m4a", "2" to "https://audio/2.m4a")
            ),
            songPreviewPlayer = player
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updatePreviewPrefetch(viewModel.uiState.value.matchup)
        advanceUntilIdle()

        viewModel.togglePreview("1")
        runCurrent()
        assertEquals(SongPreviewState.Playing, viewModel.uiState.value.previews["1"])

        viewModel.togglePreview("2")
        runCurrent()
        assertEquals(SongPreviewState.Available, viewModel.uiState.value.previews["1"])
        assertEquals(SongPreviewState.Playing, viewModel.uiState.value.previews["2"])

        viewModel.rankWinner("1", "2")
        runCurrent()
        assertEquals(1, player.stopCount)
        assertEquals(SongPreviewState.Available, viewModel.uiState.value.previews["2"])
    }

    @Test
    fun `stop preview releases paused playback`() = runTest {
        val songs = listOf(
            fakeSong(id = "1", title = "Dreams"),
            fakeSong(id = "2", title = "Go Your Own Way")
        )
        val player = FakeSongPreviewPlayer()
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = FakeRankingRepository(),
            songPreviewResolver = FakeSongPreviewResolver(
                mapOf("1" to "https://audio/1.m4a", "2" to "https://audio/2.m4a")
            ),
            songPreviewPlayer = player
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updatePreviewPrefetch(viewModel.uiState.value.matchup)
        advanceUntilIdle()

        viewModel.togglePreview("1")
        runCurrent()
        viewModel.togglePreview("1")
        runCurrent()

        assertEquals(SongPreviewState.Available, viewModel.uiState.value.previews["1"])

        viewModel.stopPreview()
        runCurrent()

        assertEquals(1, player.stopCount)
    }

    @Test
    fun `transient playback start failure leaves preview retryable`() = runTest {
        val songs = listOf(
            fakeSong(id = "1", title = "Dreams"),
            fakeSong(id = "2", title = "Go Your Own Way")
        )
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = FakeRankingRepository(),
            songPreviewResolver = FakeSongPreviewResolver(
                mapOf("1" to "https://audio/1.m4a", "2" to "https://audio/2.m4a")
            ),
            songPreviewPlayer = ThrowingSongPreviewPlayer()
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updatePreviewPrefetch(viewModel.uiState.value.matchup)
        advanceUntilIdle()

        viewModel.togglePreview("1")
        runCurrent()

        assertEquals(SongPreviewState.Available, viewModel.uiState.value.previews["1"])
    }

    @Test
    fun `observing ui state does not prefetch previews until activated`() = runTest {
        val songs = listOf(
            fakeSong(id = "1", title = "Dreams"),
            fakeSong(id = "2", title = "Go Your Own Way")
        )
        val resolver = FakeSongPreviewResolver(
            mapOf("1" to "https://audio/1.m4a", "2" to "https://audio/2.m4a")
        )
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = FakeRankingRepository(),
            songPreviewResolver = resolver,
            songPreviewPlayer = FakeSongPreviewPlayer()
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()
        assertEquals(emptyList<String>(), resolver.calls)

        viewModel.updatePreviewPrefetch(viewModel.uiState.value.matchup)
        advanceUntilIdle()

        assertEquals(listOf("1", "2"), resolver.calls.sorted())
    }

    @Test
    fun `matchup previews are prefetched and expose availability`() = runTest {
        val songs = listOf(
            fakeSong(id = "1", title = "Dreams"),
            fakeSong(id = "2", title = "Go Your Own Way")
        )
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = FakeRankingRepository(),
            songPreviewResolver = FakeSongPreviewResolver(mapOf("1" to "https://audio/1.m4a")),
            songPreviewPlayer = FakeSongPreviewPlayer()
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updatePreviewPrefetch(viewModel.uiState.value.matchup)

        advanceUntilIdle()

        assertEquals(SongPreviewState.Available, viewModel.uiState.value.previews["1"])
        assertEquals(SongPreviewState.Unavailable, viewModel.uiState.value.previews["2"])
    }

    @Test
    fun `stale preview prefetch results are ignored after matchup changes`() = runTest {
        val one = fakeSong(id = "1", title = "Dreams")
        val two = fakeSong(id = "2", title = "Go Your Own Way")
        val three = fakeSong(id = "3", title = "Rhiannon")
        val songRepository = FakeRankSongRepository(listOf(one, two))
        val resolver = DeferredSongPreviewResolver()
        val viewModel = RankViewModel(
            songRepository = songRepository,
            rankingRepository = FakeRankingRepository(),
            songPreviewResolver = resolver,
            songPreviewPlayer = FakeSongPreviewPlayer()
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        runCurrent()
        viewModel.updatePreviewPrefetch(viewModel.uiState.value.matchup)
        runCurrent()

        val staleOneCall = resolver.calls.first { it.songId == "1" }
        val staleTwoCall = resolver.calls.first { it.songId == "2" }

        songRepository.setSongs(listOf(one, three))
        runCurrent()
        viewModel.updatePreviewPrefetch(viewModel.uiState.value.matchup)
        runCurrent()

        assertEquals(2, resolver.calls.count { it.songId == "1" })
        val currentOneCall = resolver.calls.last { it.songId == "1" }
        val currentThreeCall = resolver.calls.first { it.songId == "3" }

        currentOneCall.complete("https://audio/1.m4a")
        currentThreeCall.complete("https://audio/3.m4a")
        runCurrent()
        assertEquals(SongPreviewState.Available, viewModel.uiState.value.previews["1"])

        staleOneCall.complete(null)
        staleTwoCall.complete(null)
        runCurrent()

        assertEquals(SongPreviewState.Available, viewModel.uiState.value.previews["1"])
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
        assertEquals(RankMessage.NeedTwoSongs, viewModel.uiState.value.message)
    }

    @Test
    fun `rank winner updates streak and emits transient choice feedback`() = runTest {
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

        val feedback = viewModel.uiState.value.visualFeedback
        assertTrue(feedback is RankVisualFeedback.Choice)
        feedback as RankVisualFeedback.Choice
        assertEquals("1", feedback.winnerId)
        assertEquals("2", feedback.loserId)
        assertEquals(16, feedback.winnerRatingChange)
        assertEquals(-16, feedback.loserRatingChange)
        assertEquals(1, viewModel.uiState.value.streakCount)

        advanceTimeBy(326)
        advanceUntilIdle()

        assertEquals(RankVisualFeedback.None, viewModel.uiState.value.visualFeedback)
    }

    @Test
    fun `failed rank save does not emit successful choice feedback`() = runTest {
        val songs = listOf(
            fakeSong(id = "1", title = "Dreams"),
            fakeSong(id = "2", title = "Go Your Own Way")
        )
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = FakeRankingRepository(battleResult = Result.failure(IllegalStateException("db failed")))
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()
        viewModel.rankWinner("1", "2")
        runCurrent()

        assertEquals(RankVisualFeedback.None, viewModel.uiState.value.visualFeedback)
        assertEquals(0, viewModel.uiState.value.streakCount)
        assertEquals(RankMessage.BattleSaveFailed, viewModel.uiState.value.message)
    }

    @Test
    fun `rapid ranking actions submit only the first operation`() = runTest {
        val songs = listOf(fakeSong("1", "Dreams"), fakeSong("2", "Go Your Own Way"))
        val repository = BlockingRankingRepository()
        val viewModel = RankViewModel(FakeRankSongRepository(songs), repository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.rankWinner("1", "2")
        runCurrent()
        viewModel.rankWinner("1", "2")
        viewModel.skip()
        runCurrent()

        assertTrue(viewModel.uiState.value.isSaving)
        assertEquals(1, repository.battleCalls)
        assertEquals(0, repository.skipCalls)

        repository.completeBattle(Result.success(Unit))
        runCurrent()

        assertFalse(viewModel.uiState.value.isSaving)
        assertTrue(viewModel.uiState.value.visualFeedback is RankVisualFeedback.Choice)
    }

    @Test
    fun `pending save keeps the displayed matchup stable`() = runTest {
        val songs = listOf(
            fakeSong("1", "Dreams"),
            fakeSong("2", "Go Your Own Way"),
            fakeSong("3", "Rhiannon")
        )
        val repository = BlockingRankingRepository()
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = repository,
            matchupEngine = EloMatchupEngine(AlternatingMatchupRandom())
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        val displayedMatchup = checkNotNull(viewModel.uiState.value.matchup)

        viewModel.rankWinner(displayedMatchup.left.id, displayedMatchup.right.id)
        runCurrent()

        assertTrue(viewModel.uiState.value.isSaving)
        assertEquals(displayedMatchup, viewModel.uiState.value.matchup)

        repository.completeBattle(Result.success(Unit))
        runCurrent()
    }

    @Test
    fun `skip records the matchup that was visible before saving state changes`() = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val songs = listOf(
            fakeSong("1", "Dreams"),
            fakeSong("2", "Go Your Own Way"),
            fakeSong("3", "Rhiannon")
        )
        val repository = FakeRankingRepository()
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = repository,
            matchupEngine = EloMatchupEngine(AlternatingMatchupRandom())
        )
        backgroundScope.launch(mainDispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        val displayedMatchup = checkNotNull(viewModel.uiState.value.matchup)

        viewModel.skip()
        runCurrent()

        assertEquals(
            setOf(displayedMatchup.left.id, displayedMatchup.right.id),
            repository.skippedSongIds.toSet()
        )
    }

    @Test
    fun `successful save ignores duplicate actions until feedback clears`() = runTest {
        val songs = listOf(fakeSong("1", "Dreams"), fakeSong("2", "Go Your Own Way"))
        val repository = FakeRankingRepository()
        val viewModel = RankViewModel(FakeRankSongRepository(songs), repository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.rankWinner("1", "2")
        runCurrent()
        assertTrue(viewModel.uiState.value.visualFeedback is RankVisualFeedback.Choice)

        viewModel.rankWinner("1", "2")
        viewModel.skip()
        runCurrent()

        assertEquals(1, repository.battleCalls)
        assertTrue(repository.skippedSongIds.isEmpty())

        advanceTimeBy(326)
        runCurrent()
        viewModel.rankWinner("1", "2")
        runCurrent()

        assertEquals(2, repository.battleCalls)
    }

    @Test
    fun `failed save clears guard so ranking can be retried`() = runTest {
        val songs = listOf(fakeSong("1", "Dreams"), fakeSong("2", "Go Your Own Way"))
        val repository = RetryableRankingRepository()
        val viewModel = RankViewModel(FakeRankSongRepository(songs), repository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.rankWinner("1", "2")
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSaving)
        assertEquals(RankVisualFeedback.None, viewModel.uiState.value.visualFeedback)

        viewModel.rankWinner("1", "2")
        runCurrent()

        assertEquals(2, repository.battleCalls)
        assertTrue(viewModel.uiState.value.visualFeedback is RankVisualFeedback.Choice)
    }

    @Test
    fun `failed follow-up save clears earlier success feedback`() = runTest {
        val songs = listOf(fakeSong("1", "Dreams"), fakeSong("2", "Go Your Own Way"))
        val repository = SucceedsThenFailsRankingRepository()
        val viewModel = RankViewModel(FakeRankSongRepository(songs), repository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.rankWinner("1", "2")
        runCurrent()
        assertTrue(viewModel.uiState.value.visualFeedback is RankVisualFeedback.Choice)

        advanceTimeBy(326)
        runCurrent()
        viewModel.rankWinner("1", "2")
        runCurrent()

        assertFalse(viewModel.uiState.value.isSaving)
        assertEquals(RankVisualFeedback.None, viewModel.uiState.value.visualFeedback)
        assertEquals(RankMessage.BattleSaveFailed, viewModel.uiState.value.message)
    }

    @Test
    fun `skip resets streak and emits transient skip feedback`() = runTest {
        val songs = listOf(
            fakeSong(id = "1", title = "Dreams"),
            fakeSong(id = "2", title = "Go Your Own Way")
        )
        val rankingRepository = FakeRankingRepository()
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = rankingRepository
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()
        val matchup = checkNotNull(viewModel.uiState.value.matchup)
        viewModel.rankWinner("1", "2")
        runCurrent()

        advanceTimeBy(326)
        runCurrent()

        viewModel.skip()
        runCurrent()

        assertEquals(0, viewModel.uiState.value.streakCount)
        assertEquals(RankVisualFeedback.Skip, viewModel.uiState.value.visualFeedback)
        assertEquals(setOf(matchup.left.id, matchup.right.id), rankingRepository.skippedSongIds.toSet())

        advanceTimeBy(326)
        advanceUntilIdle()

        assertEquals(RankVisualFeedback.None, viewModel.uiState.value.visualFeedback)
    }
}

private class FakeSongPreviewResolver(
    private val urls: Map<String, String?>
) : SongPreviewResolver {
    val calls = mutableListOf<String>()

    override suspend fun resolve(song: Song): String? {
        calls += song.id
        return urls[song.id]
    }
}

private class AlternatingMatchupRandom : Random() {
    private var nextLeftIndex = 0

    override fun nextBits(bitCount: Int): Int = 0

    override fun nextInt(until: Int): Int = when (until) {
        3 -> nextLeftIndex.also { nextLeftIndex = if (nextLeftIndex == 0) 2 else 0 }
        2 -> 0
        else -> 0
    }
}

private class DeferredSongPreviewResolver : SongPreviewResolver {
    val calls = mutableListOf<PreviewCall>()

    override suspend fun resolve(song: Song): String? {
        val call = PreviewCall(song.id)
        calls += call
        return withContext(NonCancellable) { call.result.await() }
    }
}

private class PreviewCall(val songId: String) {
    val result = CompletableDeferred<String?>()

    fun complete(url: String?) {
        result.complete(url)
    }
}

private class FakeSongPreviewPlayer : SongPreviewPlayer {
    private val mutableEvents = MutableSharedFlow<com.songladder.android.domain.repository.SongPreviewPlaybackEvent>(extraBufferCapacity = 1)
    override val events = mutableEvents
    var stopCount = 0
    override fun play(songId: String, url: String) = Unit
    override fun pause() = Unit
    override fun stop() { stopCount += 1 }
    fun fail(songId: String) {
        mutableEvents.tryEmit(com.songladder.android.domain.repository.SongPreviewPlaybackEvent(songId, failed = true))
    }
}

private class ThrowingSongPreviewPlayer : SongPreviewPlayer {
    override val events = kotlinx.coroutines.flow.emptyFlow<com.songladder.android.domain.repository.SongPreviewPlaybackEvent>()
    override fun play(songId: String, url: String) {
        error("Audio focus is unavailable.")
    }
    override fun pause() = Unit
    override fun stop() = Unit
}

private class FakeRankSongRepository(
    songs: List<Song>
) : SongRepository {
    private val songFlow = MutableStateFlow(songs)

    override fun observeSongs(): Flow<List<Song>> = songFlow

    fun setSongs(songs: List<Song>) {
        songFlow.value = songs
    }

    override suspend fun addSong(input: com.songladder.android.domain.model.SongInput): Result<Unit> = Result.success(Unit)

    override suspend fun removeSong(songId: String): Result<Unit> = Result.success(Unit)

    override suspend fun resetLibrary(): Result<Unit> = Result.success(Unit)
}

private class FakeRankingRepository(
    private val battleResult: Result<Unit> = Result.success(Unit),
    private val skipResult: Result<Unit> = Result.success(Unit)
) : RankingRepository {
    private val stats = MutableStateFlow(AppStats())
    val skippedSongIds = mutableListOf<String>()
    var battleCalls = 0

    override fun observeStats(): Flow<AppStats> = stats

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> {
        battleCalls += 1
        if (battleResult.isSuccess) {
            stats.value = stats.value.copy(matchCount = stats.value.matchCount + 1)
        }
        return battleResult
    }

    override suspend fun recordSkip(songIds: List<String>): Result<Unit> {
        if (skipResult.isSuccess) {
            skippedSongIds += songIds
            stats.value = stats.value.copy(skipCount = stats.value.skipCount + 1)
        }
        return skipResult
    }
}

private class BlockingRankingRepository : RankingRepository {
    private val stats = MutableStateFlow(AppStats())
    private var battleResult = CompletableDeferred<Result<Unit>>()
    var battleCalls = 0
    var skipCalls = 0

    override fun observeStats(): Flow<AppStats> = stats

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> {
        battleCalls += 1
        return battleResult.await()
    }

    override suspend fun recordSkip(songIds: List<String>): Result<Unit> {
        skipCalls += 1
        return Result.success(Unit)
    }

    fun completeBattle(result: Result<Unit>) {
        battleResult.complete(result)
    }
}

private class RetryableRankingRepository : RankingRepository {
    private val stats = MutableStateFlow(AppStats())
    var battleCalls = 0

    override fun observeStats(): Flow<AppStats> = stats

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> {
        battleCalls += 1
        return if (battleCalls == 1) {
            Result.failure(IllegalStateException("db failed"))
        } else {
            Result.success(Unit)
        }
    }

    override suspend fun recordSkip(songIds: List<String>): Result<Unit> = Result.success(Unit)
}

private class SucceedsThenFailsRankingRepository : RankingRepository {
    private val stats = MutableStateFlow(AppStats())
    var battleCalls = 0

    override fun observeStats(): Flow<AppStats> = stats

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> {
        battleCalls += 1
        return if (battleCalls == 1) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("db failed"))
        }
    }

    override suspend fun recordSkip(songIds: List<String>): Result<Unit> = Result.success(Unit)
}

private fun fakeSong(id: String, title: String): Song {
    return Song(
        id = id,
        title = title,
        artist = "Artist $id",
        createdAt = 1L
    )
}
