package com.songladder.android.ui.rankings

import com.songladder.android.domain.model.Album
import com.songladder.android.domain.model.AlbumDetail
import com.songladder.android.domain.model.AlbumMatchStatus
import com.songladder.android.domain.model.AlbumReleaseCandidate
import com.songladder.android.domain.model.AlbumReleaseTrack
import com.songladder.android.domain.model.AlbumTrackRow
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.DeletedRankingHistory
import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.RankedAlbum
import com.songladder.android.domain.model.RankingHistoryDeletionResult
import com.songladder.android.domain.model.RankingPresentation
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.ScoreSaveResult
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.model.Suggestion
import com.songladder.android.domain.repository.AlbumRepository
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SettingsRepository
import com.songladder.android.domain.repository.SongPreviewPlaybackEvent
import com.songladder.android.domain.repository.SongPreviewPlayer
import com.songladder.android.domain.repository.SongPreviewResolver
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
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
    fun `suggestion rows pair each suggestion with its matching song`() = runTest {
        val rankingRepository = FakeRankingRepository(
            suggestions = listOf(
                Suggestion(
                    subjectId = "song-1",
                    suggestedScoreTenths = 70,
                    comparisonCount = 5,
                    scoreGapTenths = null,
                    lastEventSequenceId = 5L
                )
            )
        )
        val viewModel = viewModel(
            songs = listOf(rankingsSong(id = "song-1", scoreTenths = null)),
            rankingRepository = rankingRepository
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()

        val row = viewModel.uiState.value.suggestionRows.single()
        assertEquals("song-1", row.suggestion.subjectId)
        assertEquals("song-1", row.song.id)
        assertEquals(70, row.suggestion.suggestedScoreTenths)
    }

    @Test
    fun `accepting a suggestion clears its selection`() = runTest {
        val rankingRepository = FakeRankingRepository(
            suggestions = listOf(
                Suggestion("song-1", suggestedScoreTenths = 70, comparisonCount = 5, scoreGapTenths = null, lastEventSequenceId = 5L)
            )
        )
        val viewModel = viewModel(
            songs = listOf(rankingsSong(id = "song-1", scoreTenths = null)),
            rankingRepository = rankingRepository
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.toggleSuggestionSelection("song-1")
        advanceUntilIdle()
        assertEquals(setOf("song-1"), viewModel.uiState.value.selectedSuggestionIds)

        viewModel.acceptSuggestion("song-1", 70)
        advanceUntilIdle()

        assertEquals(listOf("song-1" to 70), rankingRepository.acceptedSuggestions)
        assertEquals(emptySet<String>(), viewModel.uiState.value.selectedSuggestionIds)
    }

    @Test
    fun `dismissing a suggestion later forwards its current suggested value`() = runTest {
        val rankingRepository = FakeRankingRepository(
            suggestions = listOf(
                Suggestion("song-1", suggestedScoreTenths = 65, comparisonCount = 6, scoreGapTenths = 8, lastEventSequenceId = 9L)
            )
        )
        val viewModel = viewModel(
            songs = listOf(rankingsSong(id = "song-1", scoreTenths = 55)),
            rankingRepository = rankingRepository
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.dismissSuggestionLater("song-1")
        advanceUntilIdle()

        assertEquals(listOf("song-1"), rankingRepository.dismissedSuggestionIds)
    }

    @Test
    fun `accepting selected suggestions applies each one and clears the selection`() = runTest {
        val rankingRepository = FakeRankingRepository(
            suggestions = listOf(
                Suggestion("song-1", suggestedScoreTenths = 70, comparisonCount = 5, scoreGapTenths = null, lastEventSequenceId = 5L),
                Suggestion("song-2", suggestedScoreTenths = 40, comparisonCount = 5, scoreGapTenths = null, lastEventSequenceId = 6L)
            )
        )
        val viewModel = viewModel(
            songs = listOf(
                rankingsSong(id = "song-1", scoreTenths = null),
                rankingsSong(id = "song-2", scoreTenths = null)
            ),
            rankingRepository = rankingRepository
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.toggleSuggestionSelection("song-1")
        viewModel.toggleSuggestionSelection("song-2")
        advanceUntilIdle()

        viewModel.acceptSelectedSuggestions()
        advanceUntilIdle()

        assertEquals(
            setOf("song-1" to 70, "song-2" to 40),
            rankingRepository.acceptedSuggestions.toSet()
        )
        assertEquals(emptySet<String>(), viewModel.uiState.value.selectedSuggestionIds)
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
    fun `searching preserves each song's original rank instead of re-ranking the filtered results`() = runTest {
        val viewModel = viewModel(
            songs = listOf(
                rankingsSong(id = "one", title = "First", scoreTenths = 90),
                rankingsSong(id = "two", title = "Needle", scoreTenths = 50)
            )
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.rankedSongs.first { it.song.id == "two" }.rank)

        viewModel.setSearchActive(true)
        viewModel.updateSearchQuery("needle")
        advanceUntilIdle()

        val filtered = viewModel.uiState.value.rankedSongs
        assertEquals(listOf("two"), filtered.map { it.song.id })
        assertEquals(2, filtered.single().rank)
    }

    @Test
    fun `selecting the artists tab closes songs search and reports coming soon`() = runTest {
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

        viewModel.selectTab(RankingsTab.ARTISTS)
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.searchActive)
        assertEquals("", viewModel.uiState.value.searchQuery)
        assertEquals(RankingsTab.ARTISTS, viewModel.uiState.value.selectedTab)
        assertEquals(RankingsStatus.ComingSoon, viewModel.uiState.value.status)

        viewModel.selectTab(RankingsTab.SONGS)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.rankedSongs.size)
    }

    @Test
    fun `selecting the albums tab closes songs search without reporting coming soon`() = runTest {
        val viewModel = viewModel(
            songs = listOf(rankingsSong(id = "one", title = "Needle"))
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
        assertEquals(RankingsStatus.None, viewModel.uiState.value.status)
    }

    @Test
    fun `albums split into ranked and incomplete based on whether they have a computed score`() = runTest {
        val albumRepository = FakeAlbumRepository(
            albums = listOf(
                rankedAlbum(id = "complete-low", scoreTenths = 60),
                rankedAlbum(id = "complete-high", scoreTenths = 90),
                rankedAlbum(id = "incomplete-new", scoreTenths = null, createdAt = 5L),
                rankedAlbum(id = "incomplete-old", scoreTenths = null, createdAt = 1L)
            )
        )
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()

        assertEquals(
            listOf("complete-high", "complete-low"),
            viewModel.uiState.value.rankedAlbums.map { it.album.id }
        )
        assertEquals(listOf(1, 2), viewModel.uiState.value.rankedAlbums.map { it.rank })
        assertEquals(
            listOf("incomplete-new", "incomplete-old"),
            viewModel.uiState.value.incompleteAlbums.map { it.album.id }
        )
        assertEquals(false, viewModel.uiState.value.incompleteAlbumsExpanded)
    }

    @Test
    fun `albums search filters ranked and incomplete albums by title or artist`() = runTest {
        val albumRepository = FakeAlbumRepository(
            albums = listOf(
                rankedAlbum(id = "ranked-match", title = "Needle in a Haystack", scoreTenths = 60),
                rankedAlbum(id = "ranked-other", title = "Other", scoreTenths = 90),
                rankedAlbum(id = "incomplete-match", artist = "Needle", scoreTenths = null),
                rankedAlbum(id = "incomplete-other", artist = "Other", scoreTenths = null)
            )
        )
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        viewModel.selectTab(RankingsTab.ALBUMS)
        advanceUntilIdle()

        viewModel.setSearchActive(true)
        viewModel.updateSearchQuery("needle")
        advanceUntilIdle()

        assertEquals(listOf("ranked-match"), viewModel.uiState.value.rankedAlbums.map { it.album.id })
        assertEquals(listOf("incomplete-match"), viewModel.uiState.value.incompleteAlbums.map { it.album.id })

        viewModel.setSearchActive(false)
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.searchQuery)
        assertEquals(2, viewModel.uiState.value.rankedAlbums.size)
        assertEquals(2, viewModel.uiState.value.incompleteAlbums.size)
    }

    @Test
    fun `selecting the songs tab closes albums search`() = runTest {
        val albumRepository = FakeAlbumRepository(
            albums = listOf(rankedAlbum(id = "one", title = "Needle", scoreTenths = 60))
        )
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        viewModel.selectTab(RankingsTab.ALBUMS)
        advanceUntilIdle()

        viewModel.setSearchActive(true)
        viewModel.updateSearchQuery("needle")
        advanceUntilIdle()

        viewModel.selectTab(RankingsTab.SONGS)
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.searchActive)
        assertEquals("", viewModel.uiState.value.searchQuery)
    }

    @Test
    fun `incomplete albums start expanded when there are no ranked albums`() = runTest {
        val albumRepository = FakeAlbumRepository(
            albums = listOf(rankedAlbum(id = "incomplete-only", scoreTenths = null))
        )
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.incompleteAlbumsExpanded)
    }

    @Test
    fun `toggling incomplete albums flips the expanded flag`() = runTest {
        val albumRepository = FakeAlbumRepository(
            albums = listOf(
                rankedAlbum(id = "complete", scoreTenths = 60),
                rankedAlbum(id = "incomplete", scoreTenths = null)
            )
        )
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.incompleteAlbumsExpanded)

        viewModel.toggleIncompleteAlbumsExpanded()
        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.incompleteAlbumsExpanded)
    }

    @Test
    fun `showing album details subscribes to the detail flow and hiding cancels it`() = runTest {
        val albumRepository = FakeAlbumRepository(detail = albumDetail(id = "album-1"))
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(0, albumRepository.detailSubscriptionCount)

        viewModel.showAlbumDetails("album-1")
        advanceUntilIdle()

        assertEquals(1, albumRepository.detailSubscriptionCount)
        assertEquals("album-1", viewModel.uiState.value.albumDetail?.album?.id)

        viewModel.hideAlbumDetails()
        advanceUntilIdle()

        assertEquals(1, albumRepository.detailCancellationCount)
        assertEquals(null, viewModel.uiState.value.albumDetail)
    }

    @Test
    fun `toggling a track exclusion delegates to the album repository`() = runTest {
        val albumRepository = FakeAlbumRepository(detail = albumDetail(id = "album-1"))
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        viewModel.showAlbumDetails("album-1")
        advanceUntilIdle()

        viewModel.setAlbumTrackExcluded("album-1", "song-1", excluded = true)
        advanceUntilIdle()

        assertEquals(listOf(Triple("album-1", "song-1", true)), albumRepository.excludedCalls)
    }

    @Test
    fun `adding missing tracks delegates the selected ids to the album repository`() = runTest {
        val albumRepository = FakeAlbumRepository()
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.addAlbumMissingTracks("album-1", listOf("track-1", "track-2"))
        advanceUntilIdle()

        assertEquals(listOf("album-1" to listOf("track-1", "track-2")), albumRepository.addMissingTracksCalls)
    }

    @Test
    fun `adding missing tracks with no selection does not call the repository`() = runTest {
        val albumRepository = FakeAlbumRepository()
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.addAlbumMissingTracks("album-1", emptyList())
        advanceUntilIdle()

        assertEquals(emptyList<Pair<String, List<String>>>(), albumRepository.addMissingTracksCalls)
    }

    @Test
    fun `choosing an album release delegates to the album repository`() = runTest {
        val albumRepository = FakeAlbumRepository()
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.chooseAlbumRelease("album-1", "collection-1")
        advanceUntilIdle()

        assertEquals(listOf("album-1" to "collection-1"), albumRepository.chooseReleaseCalls)
    }

    @Test
    fun `opening a needs-review album loads match candidates exactly once`() = runTest {
        val candidate = AlbumReleaseCandidate(collectionId = "collection-1", collectionName = "Blonde", artistName = "Frank Ocean")
        val albumRepository = FakeAlbumRepository(
            detail = albumDetail(id = "album-1", matchStatus = AlbumMatchStatus.NEEDS_REVIEW),
            candidatesResult = Result.success(listOf(candidate))
        )
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.showAlbumDetails("album-1")
        advanceUntilIdle()

        assertEquals(listOf("album-1"), albumRepository.searchReleaseCandidatesCalls)
        assertEquals(listOf(candidate), viewModel.uiState.value.albumMatchCandidates?.candidates)
        assertEquals(false, viewModel.uiState.value.albumMatchCandidates?.isLoading)
    }

    @Test
    fun `opening an already-matched album does not load match candidates`() = runTest {
        val albumRepository = FakeAlbumRepository(
            detail = albumDetail(id = "album-1", matchStatus = AlbumMatchStatus.AUTO_MATCHED)
        )
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.showAlbumDetails("album-1")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), albumRepository.searchReleaseCandidatesCalls)
        assertEquals(null, viewModel.uiState.value.albumMatchCandidates)
    }

    @Test
    fun `manually requesting release candidates loads them for an already-matched album`() = runTest {
        val candidate = AlbumReleaseCandidate(collectionId = "collection-1", collectionName = "Blonde", artistName = "Frank Ocean")
        val albumRepository = FakeAlbumRepository(
            detail = albumDetail(id = "album-1", matchStatus = AlbumMatchStatus.AUTO_MATCHED),
            candidatesResult = Result.success(listOf(candidate))
        )
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.showAlbumDetails("album-1")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), albumRepository.searchReleaseCandidatesCalls)

        viewModel.requestReleaseCandidates("album-1")
        advanceUntilIdle()

        assertEquals(listOf("album-1"), albumRepository.searchReleaseCandidatesCalls)
        assertEquals(listOf(candidate), viewModel.uiState.value.albumMatchCandidates?.candidates)
    }

    @Test
    fun `refreshing metadata reloads a manually requested picker instead of leaving it stuck`() = runTest {
        val candidate = AlbumReleaseCandidate(collectionId = "collection-1", collectionName = "Blonde", artistName = "Frank Ocean")
        val albumRepository = FakeAlbumRepository(
            detail = albumDetail(id = "album-1", matchStatus = AlbumMatchStatus.AUTO_MATCHED),
            candidatesResult = Result.success(listOf(candidate))
        )
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.showAlbumDetails("album-1")
        advanceUntilIdle()
        viewModel.requestReleaseCandidates("album-1")
        advanceUntilIdle()
        assertEquals(listOf("album-1"), albumRepository.searchReleaseCandidatesCalls)

        // Refreshing metadata for an already-matched album never flips it to
        // NEEDS_REVIEW, so the auto-load path in init{} won't reissue this search -
        // without reloadReleaseCandidatesIfOpenForAlbum this would leave the picker
        // stuck on its stale "loading" state forever.
        viewModel.refreshAlbumMetadata("album-1")
        advanceUntilIdle()

        assertEquals(listOf("album-1"), albumRepository.refreshMetadataCalls)
        assertEquals(listOf("album-1", "album-1"), albumRepository.searchReleaseCandidatesCalls)
        assertEquals(listOf(candidate), viewModel.uiState.value.albumMatchCandidates?.candidates)
        assertEquals(false, viewModel.uiState.value.albumMatchCandidates?.isLoading)
    }

    @Test
    fun `choosing a release invalidates cached candidates so reopening the picker re-searches`() = runTest {
        val candidate = AlbumReleaseCandidate(collectionId = "collection-1", collectionName = "Blonde", artistName = "Frank Ocean")
        val albumRepository = FakeAlbumRepository(
            detail = albumDetail(id = "album-1", matchStatus = AlbumMatchStatus.AUTO_MATCHED),
            candidatesResult = Result.success(listOf(candidate))
        )
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.showAlbumDetails("album-1")
        advanceUntilIdle()
        viewModel.requestReleaseCandidates("album-1")
        advanceUntilIdle()
        assertEquals(listOf("album-1"), albumRepository.searchReleaseCandidatesCalls)

        viewModel.chooseAlbumRelease("album-1", "collection-1")
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.albumMatchCandidates)

        viewModel.requestReleaseCandidates("album-1")
        advanceUntilIdle()

        assertEquals(listOf("album-1", "album-1"), albumRepository.searchReleaseCandidatesCalls)
    }

    @Test
    fun `refreshing album metadata delegates to the repository`() = runTest {
        val albumRepository = FakeAlbumRepository()
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshAlbumMetadata("album-1")
        advanceUntilIdle()

        assertEquals(listOf("album-1"), albumRepository.refreshMetadataCalls)
    }

    @Test
    fun `refreshing all album metadata delegates to the repository and toggles the loading flag`() = runTest {
        val albumRepository = FakeAlbumRepository()
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshAllAlbumMetadata()
        runCurrent()
        assertEquals(true, viewModel.uiState.value.isRefreshingAllAlbums)
        advanceUntilIdle()

        assertEquals(1, albumRepository.refreshAllMetadataCallCount)
        assertEquals(false, viewModel.uiState.value.isRefreshingAllAlbums)
    }

    @Test
    fun `refreshing all album metadata again while one is already in flight is a no-op`() = runTest {
        val albumRepository = FakeAlbumRepository()
        val viewModel = viewModel(albumRepository = albumRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshAllAlbumMetadata()
        viewModel.refreshAllAlbumMetadata()
        advanceUntilIdle()

        assertEquals(1, albumRepository.refreshAllMetadataCallCount)
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
    fun `missing track preview tap resolves using the track's provider id and starts playback`() = runTest {
        var resolvedSong: Song? = null
        val viewModel = viewModel(
            previewResolver = object : SongPreviewResolver {
                override suspend fun resolve(song: Song): String? {
                    resolvedSong = song
                    return "https://example.test/preview.mp3"
                }
            }
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.toggleMissingTrackPreview("Frank Ocean", "Blonde", AlbumReleaseTrack(trackId = "12345", title = "Ivy"))
        advanceUntilIdle()

        assertEquals(
            RankingsPreviewState.Playing,
            viewModel.uiState.value.previews[albumMissingTrackPreviewKey("12345")]
        )
        assertEquals("12345", resolvedSong?.externalId)
        assertEquals("Ivy", resolvedSong?.title)
        assertEquals("Frank Ocean", resolvedSong?.artist)
        assertEquals("Blonde", resolvedSong?.album)
    }

    @Test
    fun `missing track preview tap exposes unavailable state when no preview can be resolved`() = runTest {
        val viewModel = viewModel(
            previewResolver = object : SongPreviewResolver {
                override suspend fun resolve(song: Song): String? = null
            }
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.toggleMissingTrackPreview("Frank Ocean", "Blonde", AlbumReleaseTrack(trackId = "12345", title = "Ivy"))
        advanceUntilIdle()

        assertEquals(
            RankingsPreviewState.Unavailable,
            viewModel.uiState.value.previews[albumMissingTrackPreviewKey("12345")]
        )
    }

    @Test
    fun `deleting a song stores undo state and undo restores the ranking subject`() = runTest {
        val songRepository = FakeRankingsSongRepository(listOf(rankingsSong(id = "song-1")))
        val viewModel = RankingsViewModel(
            songRepository = songRepository,
            rankingRepository = FakeRankingRepository(),
            albumRepository = FakeAlbumRepository(),
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
    albumRepository: FakeAlbumRepository = FakeAlbumRepository(),
    settingsRepository: FakeSettingsRepository = FakeSettingsRepository(),
    previewResolver: SongPreviewResolver = object : SongPreviewResolver {
        override suspend fun resolve(song: Song): String? = "https://example.test/${song.id}.mp3"
    },
    previewPlayer: SongPreviewPlayer = FakeSongPreviewPlayer()
): RankingsViewModel {
    return RankingsViewModel(
        songRepository = FakeRankingsSongRepository(songs),
        rankingRepository = rankingRepository,
        albumRepository = albumRepository,
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

private class FakeRankingRepository(
    suggestions: List<Suggestion> = emptyList()
) : RankingRepository {
    val savedScores = mutableListOf<Pair<String, Int>>()
    val acceptedSuggestions = mutableListOf<Pair<String, Int>>()
    val dismissedSuggestionIds = mutableListOf<String>()
    private val suggestionsFlow = MutableStateFlow(suggestions)

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

    override fun observeSuggestions(): Flow<List<Suggestion>> = suggestionsFlow

    override suspend fun acceptSuggestion(subjectId: String, scoreTenths: Int): Result<ScoreSaveResult> {
        acceptedSuggestions += subjectId to scoreTenths
        suggestionsFlow.update { list -> list.filterNot { it.subjectId == subjectId } }
        return Result.success(ScoreSaveResult(songId = subjectId, scoreTenths = scoreTenths))
    }

    override suspend fun dismissSuggestionLater(
        subjectId: String,
        suggestedScoreTenths: Int,
        lastEventSequenceId: Long
    ): Result<Unit> {
        dismissedSuggestionIds += subjectId
        suggestionsFlow.update { list -> list.filterNot { it.subjectId == subjectId } }
        return Result.success(Unit)
    }
}

private class FakeAlbumRepository(
    albums: List<RankedAlbum> = emptyList(),
    detail: AlbumDetail? = null,
    private val candidatesResult: Result<List<AlbumReleaseCandidate>> = Result.success(emptyList())
) : AlbumRepository {
    private val albumsFlow = MutableStateFlow(albums)
    private val detailFlow = MutableStateFlow(detail)
    var detailSubscriptionCount = 0
        private set
    var detailCancellationCount = 0
        private set
    val excludedCalls = mutableListOf<Triple<String, String, Boolean>>()
    val chooseReleaseCalls = mutableListOf<Pair<String, String>>()
    val addMissingTracksCalls = mutableListOf<Pair<String, List<String>>>()
    val refreshMetadataCalls = mutableListOf<String>()
    var refreshAllMetadataCallCount = 0
        private set
    val searchReleaseCandidatesCalls = mutableListOf<String>()

    override fun observeAlbums(): Flow<List<RankedAlbum>> = albumsFlow

    override fun observeAlbumDetail(albumId: String): Flow<AlbumDetail?> = detailFlow
        .onStart { detailSubscriptionCount++ }
        .onCompletion { detailCancellationCount++ }

    override suspend fun setTrackExcluded(albumId: String, songId: String, excluded: Boolean): Result<Unit> {
        excludedCalls += Triple(albumId, songId, excluded)
        detailFlow.update { current ->
            current?.copy(
                tracks = current.tracks.map { track ->
                    if (track.song.id == songId) track.copy(excludedFromAverage = excluded) else track
                }
            )
        }
        return Result.success(Unit)
    }

    override suspend fun chooseRelease(albumId: String, providerCollectionId: String): Result<Unit> {
        chooseReleaseCalls += albumId to providerCollectionId
        return Result.success(Unit)
    }

    override suspend fun addMissingTracks(albumId: String, providerTrackIds: List<String>): Result<Int> {
        addMissingTracksCalls += albumId to providerTrackIds
        return Result.success(providerTrackIds.size)
    }

    override suspend fun refreshMetadata(albumId: String): Result<Unit> {
        refreshMetadataCalls += albumId
        return Result.success(Unit)
    }

    override suspend fun refreshAllMetadata(): Result<Unit> {
        refreshAllMetadataCallCount++
        // A real suspension point (unlike the other fakes here, which return
        // immediately) - lets tests observe isRefreshingAllAlbums mid-flight via
        // runCurrent() rather than jumping straight past it to the settled state.
        delay(1)
        return Result.success(Unit)
    }

    override suspend fun retryPendingMatches(): Result<Unit> = Result.success(Unit)

    override suspend fun searchReleaseCandidates(albumId: String): Result<List<AlbumReleaseCandidate>> {
        searchReleaseCandidatesCalls += albumId
        return candidatesResult
    }
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

private fun rankedAlbum(
    id: String,
    title: String = "Album $id",
    artist: String = "Artist $id",
    scoreTenths: Int?,
    createdAt: Long = 1L,
    matchStatus: AlbumMatchStatus = AlbumMatchStatus.AUTO_MATCHED,
    includedRatedTrackCount: Int = 0,
    totalOwnedTrackCount: Int = 0
): RankedAlbum {
    return RankedAlbum(
        rank = null,
        album = Album(
            id = id,
            title = title,
            artist = artist,
            createdAt = createdAt,
            matchStatus = matchStatus
        ),
        scoreTenths = scoreTenths,
        includedRatedTrackCount = includedRatedTrackCount,
        totalOwnedTrackCount = totalOwnedTrackCount
    )
}

private fun albumDetail(
    id: String,
    title: String = "Album $id",
    artist: String = "Artist $id",
    matchStatus: AlbumMatchStatus = AlbumMatchStatus.AUTO_MATCHED,
    tracks: List<AlbumTrackRow> = emptyList()
): AlbumDetail {
    return AlbumDetail(
        album = Album(id = id, title = title, artist = artist, matchStatus = matchStatus),
        tracks = tracks,
        missingTracks = emptyList(),
        scoreTenths = null,
        includedRatedTrackCount = 0
    )
}
