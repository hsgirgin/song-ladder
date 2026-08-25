package com.songladder.android.ui.rank

import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.MatchupOutcome
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.ScoreSaveResult
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.Suggestion
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SettingsRepository
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    fun `pending suggestion reflects the repository's suggestion list`() = runTest {
        val songs = listOf(fakeSong("1", "Dreams"), fakeSong("2", "Go Your Own Way"))
        val rankingRepository = FakeRankingRepository(
            initialSuggestions = listOf(
                Suggestion("1", suggestedScoreTenths = 70, comparisonCount = 5, scoreGapTenths = null, lastEventSequenceId = 5L)
            )
        )
        val viewModel = RankViewModel(FakeRankSongRepository(songs), rankingRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals("1", viewModel.uiState.value.pendingSuggestion?.subjectId)
    }

    @Test
    fun `accepting the pending suggestion forwards it to the repository`() = runTest {
        val songs = listOf(fakeSong("1", "Dreams"), fakeSong("2", "Go Your Own Way"))
        val rankingRepository = FakeRankingRepository(
            initialSuggestions = listOf(
                Suggestion("1", suggestedScoreTenths = 70, comparisonCount = 5, scoreGapTenths = null, lastEventSequenceId = 5L)
            )
        )
        val viewModel = RankViewModel(FakeRankSongRepository(songs), rankingRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.acceptPendingSuggestion(75)
        advanceUntilIdle()

        assertEquals(listOf("1" to 75), rankingRepository.acceptedSuggestions)
        assertEquals(null, viewModel.uiState.value.pendingSuggestion)
    }

    @Test
    fun `dismissing the pending suggestion later forwards its current values`() = runTest {
        val songs = listOf(fakeSong("1", "Dreams"), fakeSong("2", "Go Your Own Way"))
        val rankingRepository = FakeRankingRepository(
            initialSuggestions = listOf(
                Suggestion("1", suggestedScoreTenths = 70, comparisonCount = 5, scoreGapTenths = 8, lastEventSequenceId = 9L)
            )
        )
        val viewModel = RankViewModel(FakeRankSongRepository(songs), rankingRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.dismissPendingSuggestionLater()
        advanceUntilIdle()

        assertEquals(listOf("1"), rankingRepository.dismissedSuggestionIds)
        assertEquals(null, viewModel.uiState.value.pendingSuggestion)
    }

    @Test
    fun `isSavingSuggestion is true while accepting and clears once the save completes`() = runTest {
        val songs = listOf(fakeSong("1", "Dreams"), fakeSong("2", "Go Your Own Way"))
        val gate = CompletableDeferred<Unit>()
        val rankingRepository = FakeRankingRepository(
            initialSuggestions = listOf(
                Suggestion("1", suggestedScoreTenths = 70, comparisonCount = 5, scoreGapTenths = null, lastEventSequenceId = 5L)
            ),
            acceptSuggestionGate = gate
        )
        val viewModel = RankViewModel(FakeRankSongRepository(songs), rankingRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.acceptPendingSuggestion(75)
        runCurrent()

        assertTrue(viewModel.uiState.value.isSavingSuggestion)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSavingSuggestion)
    }

    @Test
    fun `a suggestion for a song outside the current list is ignored rather than blocking matchups`() = runTest {
        val songs = listOf(fakeSong("1", "Dreams"), fakeSong("2", "Go Your Own Way"))
        val rankingRepository = FakeRankingRepository(
            initialSuggestions = listOf(
                Suggestion("missing", suggestedScoreTenths = 70, comparisonCount = 5, scoreGapTenths = null, lastEventSequenceId = 5L)
            )
        )
        val viewModel = RankViewModel(FakeRankSongRepository(songs), rankingRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingSuggestion)

        viewModel.rankWinner("1", "2")
        advanceUntilIdle()

        assertEquals(1, rankingRepository.battleCalls)
    }

    @Test
    fun `stops showing a stale unrated snapshot after its suggestion is accepted mid-display`() = runTest {
        // At 15+ unrated songs, EloMatchupEngine always pairs the rated anchor with an
        // unrated song. Lock one in first (no suggestion yet, so it's directly observable).
        val ratedAnchor = fakeSong("rated-anchor", "Anchor", scoreTenths = 80)
        val unratedSongs = (1..15).map { fakeSong("unrated-$it", "Song $it") }
        val songRepository = FakeRankSongRepository(listOf(ratedAnchor) + unratedSongs)
        val rankingRepository = FakeRankingRepository()
        val viewModel = RankViewModel(songRepository, rankingRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val lockedMatchup = requireNotNull(viewModel.uiState.value.matchup)
        val unratedSideId = listOf(lockedMatchup.left, lockedMatchup.right).first { it.scoreTenths == null }.id

        // A suggestion now appears for that same song (its trailing comparisons were all
        // against rated opponents), masking the matchup behind a suggestion card without
        // resolving it -- the locked matchup stays cached underneath, still unrated.
        rankingRepository.pushSuggestion(
            Suggestion(unratedSideId, suggestedScoreTenths = 75, comparisonCount = 5, scoreGapTenths = null, lastEventSequenceId = 5L)
        )
        advanceUntilIdle()
        assertEquals(unratedSideId, viewModel.uiState.value.pendingSuggestion?.subjectId)

        // Accepting it scores the song; simulate the repository's reactive song list update
        // the same way the real Room-backed repository would after the write commits.
        viewModel.acceptPendingSuggestion(75)
        songRepository.setSongs(
            viewModel.uiState.value.songs.map { song ->
                if (song.id == unratedSideId) song.copy(scoreTenths = 75) else song
            }
        )
        advanceUntilIdle()

        // The revealed matchup must reflect the song's real, current score -- not the stale
        // unrated snapshot captured back when the matchup was first locked in.
        val liveById = viewModel.uiState.value.songs.associateBy { it.id }
        val matchup = requireNotNull(viewModel.uiState.value.matchup)
        assertEquals(liveById[matchup.left.id]?.scoreTenths, matchup.left.scoreTenths)
        assertEquals(liveById[matchup.right.id]?.scoreTenths, matchup.right.scoreTenths)
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
            fakeSong(id = "1", title = "Dreams", scoreTenths = 80),
            fakeSong(id = "2", title = "Go Your Own Way", scoreTenths = 80)
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
    fun `play tap arms autoplay and advances through available previews sequentially`() = runTest {
        val songs = listOf(
            fakeSong(id = "1", title = "Dreams"),
            fakeSong(id = "2", title = "Go Your Own Way")
        )
        val player = FakeSongPreviewPlayer()
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = FakeRankingRepository(),
            settingsRepository = FakeSettingsRepository(),
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
        assertTrue(viewModel.uiState.value.autoplayArmed)
        assertEquals(listOf("1"), player.playedSongIds)

        player.complete("1")
        runCurrent()

        assertEquals(listOf("1", "2"), player.playedSongIds)
        assertEquals(SongPreviewState.Playing, viewModel.uiState.value.previews["2"])
    }

    @Test
    fun `background disarms autoplay and stops playback`() = runTest {
        val songs = listOf(
            fakeSong(id = "1", title = "Dreams"),
            fakeSong(id = "2", title = "Go Your Own Way")
        )
        val player = FakeSongPreviewPlayer()
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = FakeRankingRepository(),
            settingsRepository = FakeSettingsRepository(),
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

        viewModel.disarmAutoplayForBackground()
        runCurrent()

        assertFalse(viewModel.uiState.value.autoplayArmed)
        assertEquals(1, player.stopCount)
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
        assertEquals("Add at least two songs to start ranking.", viewModel.uiState.value.message)
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
        assertEquals(1, viewModel.uiState.value.streakCount)

        advanceTimeBy(326)
        advanceUntilIdle()

        assertEquals(RankVisualFeedback.None, viewModel.uiState.value.visualFeedback)
    }

    @Test
    fun `rapid winner input records only one decision`() = runTest {
        val rankingRepository = FakeRankingRepository()
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(
                listOf(fakeSong(id = "1", title = "Dreams"), fakeSong(id = "2", title = "Go Your Own Way"))
            ),
            rankingRepository = rankingRepository
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()
        viewModel.rankWinner("1", "2")
        viewModel.rankWinner("1", "2")
        runCurrent()
        viewModel.rankWinner("1", "2")
        viewModel.skip()
        runCurrent()

        assertEquals(1, rankingRepository.battleCalls)
        assertEquals(emptyList<String>(), rankingRepository.skippedSongIds)
    }

    @Test
    fun `successful winner exposes undo and undo clears the feedback state`() = runTest {
        val rankingRepository = FakeRankingRepository()
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(
                listOf(fakeSong(id = "1", title = "Dreams"), fakeSong(id = "2", title = "Go Your Own Way"))
            ),
            rankingRepository = rankingRepository
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()
        viewModel.rankWinner("1", "2")
        runCurrent()
        assertTrue(viewModel.uiState.value.undoAvailable)

        viewModel.undo()
        runCurrent()

        assertEquals(1, rankingRepository.undoCalls)
        assertFalse(viewModel.uiState.value.undoAvailable)
        assertEquals(RankVisualFeedback.None, viewModel.uiState.value.visualFeedback)
    }

    @Test
    fun `winner returns straight to a normal matchup with no pending state`() = runTest {
        val rankingRepository = FakeRankingRepository()
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(
                listOf(fakeSong(id = "1", title = "Dreams"), fakeSong(id = "2", title = "Go Your Own Way"))
            ),
            rankingRepository = rankingRepository
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()
        viewModel.rankWinner("1", "2")
        runCurrent()

        assertEquals(1, rankingRepository.battleCalls)
        assertEquals(RankVisualFeedback.Choice("1", "2"), viewModel.uiState.value.visualFeedback)

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
        assertEquals("Could not save ranking. Try again.", viewModel.uiState.value.message)
    }

    @Test
    fun `skip resets streak and emits transient skip feedback`() = runTest {
        val songs = listOf(
            fakeSong(id = "1", title = "Dreams", scoreTenths = 80),
            fakeSong(id = "2", title = "Go Your Own Way", scoreTenths = 80)
        )
        val rankingRepository = FakeRankingRepository()
        val viewModel = RankViewModel(
            songRepository = FakeRankSongRepository(songs),
            rankingRepository = rankingRepository
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()
        val matchup = checkNotNull(viewModel.uiState.value.matchup)
        viewModel.skip()
        runCurrent()

        assertEquals(0, viewModel.uiState.value.streakCount)
        assertEquals(RankVisualFeedback.Skip, viewModel.uiState.value.visualFeedback)
        assertEquals(setOf(matchup.left.id, matchup.right.id), rankingRepository.skippedSongIds.toSet())

        advanceTimeBy(326)
        advanceUntilIdle()

        assertEquals(RankVisualFeedback.None, viewModel.uiState.value.visualFeedback)
    }

    @Test
    fun `caught up state offers continue anyway through the production view model`() = runTest {
        val songs = listOf(
            fakeSong(id = "1", title = "One"),
            fakeSong(id = "2", title = "Two"),
            fakeSong(id = "3", title = "Three")
        )
        val rankingRepository = FakeRankingRepository(
            initialEvents = listOf(
                skipEvent(1L, "1", "2"),
                skipEvent(2L, "2", "3"),
                skipEvent(3L, "1", "3")
            )
        )
        val viewModel = RankViewModel(FakeRankSongRepository(songs), rankingRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.caughtUp)
        assertEquals(null, viewModel.uiState.value.matchup)

        viewModel.continueAnyway()
        runCurrent()

        assertTrue(!viewModel.uiState.value.caughtUp)
        assertTrue(viewModel.uiState.value.matchup != null)
    }
}

private fun skipEvent(sequenceId: Long, first: String, second: String) = MatchupEvent(
    sequenceId = sequenceId,
    occurredAt = sequenceId,
    firstSubjectId = first,
    secondSubjectId = second,
    outcome = MatchupOutcome.SKIP
)

private class FakeSongPreviewResolver(
    private val urls: Map<String, String?>
) : SongPreviewResolver {
    val calls = mutableListOf<String>()

    override suspend fun resolve(song: Song): String? {
        calls += song.id
        return urls[song.id]
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
    val playedSongIds = mutableListOf<String>()
    override fun play(songId: String, url: String) {
        playedSongIds += songId
    }
    override fun pause() = Unit
    override fun stop() { stopCount += 1 }
    fun complete(songId: String) {
        mutableEvents.tryEmit(com.songladder.android.domain.repository.SongPreviewPlaybackEvent(songId))
    }
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
    private val skipResult: Result<Unit> = Result.success(Unit),
    private val undoResult: Result<Boolean> = Result.success(true),
    initialEvents: List<MatchupEvent> = emptyList(),
    initialSuggestions: List<Suggestion> = emptyList(),
    private val acceptSuggestionGate: CompletableDeferred<Unit>? = null
) : RankingRepository {
    private val stats = MutableStateFlow(AppStats())
    private val events = MutableStateFlow(initialEvents)
    private val suggestions = MutableStateFlow(initialSuggestions)
    val skippedSongIds = mutableListOf<String>()
    val acceptedSuggestions = mutableListOf<Pair<String, Int>>()
    val dismissedSuggestionIds = mutableListOf<String>()
    var battleCalls = 0
    var undoCalls = 0

    override fun observeStats(): Flow<AppStats> = stats

    override fun observeMatchupEvents(): Flow<List<MatchupEvent>> = events

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

    override suspend fun saveScore(songId: String, scoreTenths: Int): Result<ScoreSaveResult> {
        return Result.success(ScoreSaveResult(songId = songId, scoreTenths = scoreTenths))
    }

    override suspend fun undoLastWinner(): Result<Boolean> {
        undoCalls += 1
        return undoResult
    }

    override fun observeSuggestions(): Flow<List<Suggestion>> = suggestions

    fun pushSuggestion(suggestion: Suggestion) {
        suggestions.update { it + suggestion }
    }

    override suspend fun acceptSuggestion(subjectId: String, scoreTenths: Int): Result<ScoreSaveResult> {
        acceptSuggestionGate?.await()
        acceptedSuggestions += subjectId to scoreTenths
        suggestions.update { list -> list.filterNot { it.subjectId == subjectId } }
        return Result.success(ScoreSaveResult(songId = subjectId, scoreTenths = scoreTenths))
    }

    override suspend fun dismissSuggestionLater(
        subjectId: String,
        suggestedScoreTenths: Int,
        lastEventSequenceId: Long
    ): Result<Unit> {
        dismissedSuggestionIds += subjectId
        suggestions.update { list -> list.filterNot { it.subjectId == subjectId } }
        return Result.success(Unit)
    }
}

private class FakeSettingsRepository(
    initialSettings: RankingSettings = RankingSettings()
) : SettingsRepository {
    private val settings = MutableStateFlow(initialSettings)

    override fun observeSettings(): Flow<RankingSettings> = settings

    override suspend fun saveSettings(settings: RankingSettings): Result<Unit> {
        this.settings.value = settings
        return Result.success(Unit)
    }
}

private fun fakeSong(id: String, title: String, scoreTenths: Int? = null): Song {
    return Song(
        id = id,
        title = title,
        artist = "Artist $id",
        createdAt = 1L,
        scoreTenths = scoreTenths
    )
}
