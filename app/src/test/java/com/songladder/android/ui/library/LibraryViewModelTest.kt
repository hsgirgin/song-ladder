package com.songladder.android.ui.library

import android.content.ContentResolver
import android.net.Uri
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.PlaylistImportPreview
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.ScoreSaveResult
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.model.TombstoneImportAction
import com.songladder.android.domain.model.TombstoneImportConflict
import com.songladder.android.domain.model.TombstoneImportMatch
import com.songladder.android.domain.model.TombstoneImportResolution
import com.songladder.android.domain.repository.ImportRepository
import com.songladder.android.domain.repository.MusicSourceClient
import com.songladder.android.domain.repository.PlaylistSourceClient
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SettingsRepository
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val viewModel = viewModel(
            songRepository = fakeSongRepository,
            importRepository = FakeImportRepository(fakeSongRepository),
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
        val viewModel = viewModel(musicSourceClient = musicSourceClient)
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
        val songRepository = FakeSongRepository()
        val importRepository = FakeImportRepository(songRepository)
        val viewModel = viewModel(songRepository = songRepository, importRepository = importRepository)
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

        assertEquals(1, importRepository.imported.size)
        assertEquals("Added Nights to your ladder.", viewModel.uiState.value.statusMessage)
        assertTrue("1" in viewModel.uiState.value.addedTrackIds)
    }

    @Test
    fun `search result that fuzzy-matches an existing song prompts an ambiguous match instead of importing`() = runTest {
        val existingMatch = Song(
            id = "song-existing",
            rankingSubjectId = "song-existing",
            title = "Nights (Remastered)",
            artist = "Frank Ocean",
            createdAt = 0L
        )
        val songRepository = FakeSongRepository(ambiguousMatchesProvider = { Result.success(listOf(existingMatch)) })
        val importRepository = FakeImportRepository(songRepository)
        val viewModel = viewModel(songRepository = songRepository, importRepository = importRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        val candidate = MusicTrackCandidate(
            externalId = "1",
            title = "Nights",
            artist = "Frank Ocean",
            album = "Blonde",
            sourceType = MusicSourceType.ITUNES
        )

        viewModel.addSearchResult(candidate)
        advanceUntilIdle()

        assertEquals(candidate, viewModel.uiState.value.ambiguousMatch?.candidate)
        assertEquals(listOf(existingMatch), viewModel.uiState.value.ambiguousMatch?.matches)
        assertTrue(importRepository.imported.isEmpty())
    }

    @Test
    fun `confirming an ambiguous match as the same song dismisses it without importing`() = runTest {
        val existingMatch = Song(
            id = "song-existing",
            rankingSubjectId = "song-existing",
            title = "Nights (Remastered)",
            artist = "Frank Ocean",
            createdAt = 0L
        )
        val songRepository = FakeSongRepository(ambiguousMatchesProvider = { Result.success(listOf(existingMatch)) })
        val importRepository = FakeImportRepository(songRepository)
        val viewModel = viewModel(songRepository = songRepository, importRepository = importRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        val candidate = MusicTrackCandidate(
            externalId = "1",
            title = "Nights",
            artist = "Frank Ocean",
            album = "Blonde",
            sourceType = MusicSourceType.ITUNES
        )
        viewModel.addSearchResult(candidate)
        advanceUntilIdle()

        viewModel.confirmAmbiguousMatchIsSameSong()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.ambiguousMatch)
        assertTrue(importRepository.imported.isEmpty())
        assertEquals("Nights is already in your ladder.", viewModel.uiState.value.statusMessage)
    }

    @Test
    fun `adding an ambiguous match as new proceeds with the original import`() = runTest {
        val existingMatch = Song(
            id = "song-existing",
            rankingSubjectId = "song-existing",
            title = "Nights (Remastered)",
            artist = "Frank Ocean",
            createdAt = 0L
        )
        val songRepository = FakeSongRepository(ambiguousMatchesProvider = { Result.success(listOf(existingMatch)) })
        val importRepository = FakeImportRepository(songRepository)
        val viewModel = viewModel(songRepository = songRepository, importRepository = importRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        val candidate = MusicTrackCandidate(
            externalId = "1",
            title = "Nights",
            artist = "Frank Ocean",
            album = "Blonde",
            sourceType = MusicSourceType.ITUNES
        )
        viewModel.addSearchResult(candidate)
        advanceUntilIdle()

        viewModel.addAmbiguousMatchAsNew()
        runCurrent()

        assertNull(viewModel.uiState.value.ambiguousMatch)
        assertEquals(1, importRepository.imported.size)
        assertTrue("1" in viewModel.uiState.value.addedTrackIds)
    }

    @Test
    fun `cancelling an ambiguous match clears it without importing`() = runTest {
        val existingMatch = Song(
            id = "song-existing",
            rankingSubjectId = "song-existing",
            title = "Nights (Remastered)",
            artist = "Frank Ocean",
            createdAt = 0L
        )
        val songRepository = FakeSongRepository(ambiguousMatchesProvider = { Result.success(listOf(existingMatch)) })
        val importRepository = FakeImportRepository(songRepository)
        val viewModel = viewModel(songRepository = songRepository, importRepository = importRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        val candidate = MusicTrackCandidate(
            externalId = "1",
            title = "Nights",
            artist = "Frank Ocean",
            album = "Blonde",
            sourceType = MusicSourceType.ITUNES
        )
        viewModel.addSearchResult(candidate)
        advanceUntilIdle()

        viewModel.cancelAmbiguousMatch()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.ambiguousMatch)
        assertTrue(importRepository.imported.isEmpty())
        assertEquals("Import cancelled.", viewModel.uiState.value.statusMessage)
    }

    @Test
    fun `manual add opens a single-song rating queue`() = runTest {
        val songRepository = FakeSongRepository()
        val viewModel = viewModel(songRepository = songRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.addSong(title = "Nights", artist = "Frank Ocean", album = "Blonde")
        advanceUntilIdle()

        val queue = viewModel.uiState.value.ratingQueue
        assertNotNull(queue)
        assertEquals(ImportRatingQueueKind.SINGLE_SONG, queue?.kind)
        assertEquals("Song added to your ladder.", viewModel.uiState.value.statusMessage)
        assertEquals("Nights", viewModel.uiState.value.songs.single().title)
    }

    @Test
    fun `restoring a tombstoned song does not queue it for rating`() = runTest {
        val songRepository = FakeSongRepository()
        val candidate = MusicTrackCandidate(
            externalId = "1",
            title = "Nights",
            artist = "Frank Ocean",
            album = "Blonde",
            sourceType = MusicSourceType.ITUNES
        )
        val conflict = TombstoneImportConflict(
            candidate = candidate,
            matches = listOf(
                TombstoneImportMatch(
                    rankingSubjectId = "subject-1",
                    title = "Nights",
                    artist = "Frank Ocean",
                    sourceType = MusicSourceType.ITUNES,
                    externalId = "1"
                )
            )
        )
        val importRepository = FakeImportRepository(
            songRepository,
            tombstoneMatches = mapOf(importKeyFor(candidate) to conflict)
        )
        val viewModel = viewModel(songRepository = songRepository, importRepository = importRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.addSearchResult(candidate)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.tombstoneConflict)

        viewModel.resolveTombstoneConflict(
            TombstoneImportResolution(TombstoneImportAction.RESTORE, rankingSubjectId = "subject-1")
        )
        advanceUntilIdle()

        assertEquals(80, viewModel.uiState.value.songs.single { it.title == "Nights" }.scoreTenths)
        assertNull(
            "a restored song already has its score back, so it should not enter the post-import rating queue",
            viewModel.uiState.value.ratingQueue
        )
    }

    @Test
    fun `starting fresh on a tombstoned song still queues it for rating`() = runTest {
        val songRepository = FakeSongRepository()
        val candidate = MusicTrackCandidate(
            externalId = "1",
            title = "Nights",
            artist = "Frank Ocean",
            album = "Blonde",
            sourceType = MusicSourceType.ITUNES
        )
        val conflict = TombstoneImportConflict(
            candidate = candidate,
            matches = listOf(
                TombstoneImportMatch(
                    rankingSubjectId = "subject-1",
                    title = "Nights",
                    artist = "Frank Ocean",
                    sourceType = MusicSourceType.ITUNES,
                    externalId = "1"
                )
            )
        )
        val importRepository = FakeImportRepository(
            songRepository,
            tombstoneMatches = mapOf(importKeyFor(candidate) to conflict)
        )
        val viewModel = viewModel(songRepository = songRepository, importRepository = importRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.addSearchResult(candidate)
        advanceUntilIdle()

        viewModel.resolveTombstoneConflict(TombstoneImportResolution(TombstoneImportAction.START_FRESH))
        advanceUntilIdle()

        assertNotNull(
            "a song the user chose to start fresh on still needs its first rating",
            viewModel.uiState.value.ratingQueue
        )
    }

    @Test
    fun `playlist queue save and skip advance to completion summary`() = runTest {
        val songRepository = FakeSongRepository()
        val importRepository = FakeImportRepository(songRepository)
        val rankingRepository = FakeRankingRepository()
        val preview = PlaylistImportPreview(
            playlistTitle = "Drive Home",
            importableTracks = listOf(
                MusicTrackCandidate(
                    externalId = "ytm-1",
                    title = "Midnight City",
                    artist = "M83",
                    sourceType = MusicSourceType.YOUTUBE_MUSIC
                ),
                MusicTrackCandidate(
                    externalId = "ytm-2",
                    title = "Intro",
                    artist = "The xx",
                    sourceType = MusicSourceType.YOUTUBE_MUSIC
                )
            ),
            ambiguousTracks = emptyList()
        )
        val viewModel = viewModel(
            songRepository = songRepository,
            importRepository = importRepository,
            playlistSourceClient = FakePlaylistSourceClient(Result.success(preview)),
            rankingRepository = rankingRepository
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.updateYoutubeMusicPlaylistUrl("https://music.youtube.com/playlist?list=PL123")
        viewModel.previewYoutubeMusicPlaylist()
        advanceUntilIdle()
        viewModel.confirmYoutubeMusicPreviewImport()
        advanceUntilIdle()

        assertEquals(ImportRatingQueueKind.PLAYLIST, viewModel.uiState.value.ratingQueue?.kind)

        viewModel.updateQueueDraftScore(84)
        runCurrent()
        viewModel.saveQueueScore()
        advanceUntilIdle()

        assertEquals(listOf("Midnight City" to 84), rankingRepository.savedScores.map { (id, score) ->
            songRepository.titleFor(id) to score
        })
        assertEquals(1, viewModel.uiState.value.ratingQueue?.currentIndex)

        viewModel.skipQueueSong()
        advanceUntilIdle()

        val completion = viewModel.uiState.value.ratingQueue?.completion
        assertNotNull(completion)
        assertEquals(1, completion?.ratedCount)
        assertEquals(1, completion?.skippedCount)
    }

    @Test
    fun `import while a rating queue is active waits its turn instead of being dropped`() = runTest {
        val songRepository = FakeSongRepository()
        val viewModel = viewModel(songRepository = songRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.addSong(title = "Dreams", artist = "Fleetwood Mac", album = "Rumours")
        advanceUntilIdle()
        val dreamsId = viewModel.uiState.value.ratingQueue?.currentSongId
        assertEquals("Dreams", songRepository.titleFor(requireNotNull(dreamsId)))

        viewModel.addSong(title = "Nights", artist = "Frank Ocean", album = "Blonde")
        advanceUntilIdle()

        assertEquals(
            "Dreams queue must stay on screen; the second import should not silently replace it",
            dreamsId,
            viewModel.uiState.value.ratingQueue?.currentSongId
        )

        viewModel.dismissRatingQueue()
        advanceUntilIdle()

        val nightsId = viewModel.uiState.value.ratingQueue?.currentSongId
        assertNotNull("second import must still launch its own queue once the first is dismissed", nightsId)
        assertEquals("Nights", songRepository.titleFor(requireNotNull(nightsId)))
    }

    @Test
    fun `dismissing the rating queue cancels remaining songs`() = runTest {
        val songRepository = FakeSongRepository()
        val viewModel = viewModel(songRepository = songRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.addSong(title = "Dreams", artist = "Fleetwood Mac", album = "Rumours")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.ratingQueue)

        viewModel.dismissRatingQueue()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.ratingQueue)
    }

    @Test
    fun `queue save failure keeps the current song in place`() = runTest {
        val songRepository = FakeSongRepository()
        val rankingRepository = FakeRankingRepository(
            saveScoreResult = Result.failure(IllegalStateException("db failed"))
        )
        val viewModel = viewModel(
            songRepository = songRepository,
            rankingRepository = rankingRepository
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.addSong(title = "Dreams", artist = "Fleetwood Mac", album = "Rumours")
        advanceUntilIdle()
        viewModel.updateQueueDraftScore(90)
        viewModel.saveQueueScore()
        advanceUntilIdle()

        assertEquals("Dreams", viewModel.uiState.value.songs.first { it.id == viewModel.uiState.value.ratingQueue?.currentSongId }.title)
        assertEquals("Could not save score. Try again.", viewModel.uiState.value.ratingQueue?.errorMessage)
    }

    @Test
    fun `dismissing tips persists showTips off`() = runTest {
        val settingsRepository = FakeSettingsRepository()
        val viewModel = viewModel(settingsRepository = settingsRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.dismissTips()
        advanceUntilIdle()

        assertEquals(false, settingsRepository.settings.value.showTips)
        assertEquals(true, settingsRepository.settings.value.autoPlayMatchupPreviews)
    }

    @Test
    fun `json import exposes repaired ranking record count`() = runTest {
        val songRepository = FakeSongRepository()
        val viewModel = viewModel(
            songRepository = songRepository,
            importRepository = FakeImportRepository(songRepository)
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.importJson { Result.success(2) }
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.jsonImportRepairedCount)
    }

    private fun viewModel(
        songRepository: FakeSongRepository = FakeSongRepository(),
        importRepository: FakeImportRepository = FakeImportRepository(songRepository),
        musicSourceClient: FakeMusicSourceClient = FakeMusicSourceClient(emptyList()),
        playlistSourceClient: FakePlaylistSourceClient = FakePlaylistSourceClient(Result.success(emptyPreview())),
        rankingRepository: FakeRankingRepository = FakeRankingRepository(),
        settingsRepository: FakeSettingsRepository = FakeSettingsRepository()
    ): LibraryViewModel = LibraryViewModel(
        songRepository = songRepository,
        importRepository = importRepository,
        musicSourceClient = musicSourceClient,
        playlistSourceClient = playlistSourceClient,
        rankingRepository = rankingRepository,
        settingsRepository = settingsRepository
    )
}

private class FakeSongRepository(
    private val ambiguousMatchesProvider: (MusicTrackCandidate) -> Result<List<Song>> = { Result.success(emptyList()) }
) : SongRepository {
    private val songs = MutableStateFlow<List<Song>>(emptyList())
    private var nextId = 0

    override fun observeSongs(): Flow<List<Song>> = songs

    override suspend fun findAmbiguousMatches(candidate: MusicTrackCandidate): Result<List<Song>> =
        ambiguousMatchesProvider(candidate)

    override suspend fun addSong(input: SongInput): Result<Unit> {
        val id = "song-${nextId++}"
        songs.value = songs.value + Song(
            id = id,
            rankingSubjectId = id,
            externalId = input.externalId,
            sourceType = input.sourceType,
            title = input.title.trim(),
            artist = input.artist.trim(),
            album = input.album,
            artworkUrl = input.artworkUrl,
            createdAt = nextId.toLong()
        )
        return Result.success(Unit)
    }

    override suspend fun removeSong(songId: String): Result<Unit> {
        songs.value = songs.value.filterNot { it.id == songId }
        return Result.success(Unit)
    }

    override suspend fun resetLibrary(): Result<Unit> {
        songs.value = emptyList()
        return Result.success(Unit)
    }

    fun importCandidates(candidates: List<MusicTrackCandidate>): Int {
        var inserted = 0
        candidates
            .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            .distinctBy { "${it.title.trim().lowercase()}::${it.artist.trim().lowercase()}" }
            .forEach { candidate ->
                val duplicate = songs.value.any {
                    it.title.trim().equals(candidate.title.trim(), ignoreCase = true) &&
                        it.artist.trim().equals(candidate.artist.trim(), ignoreCase = true)
                }
                if (!duplicate) {
                    val id = "song-${nextId++}"
                    songs.value = songs.value + Song(
                        id = id,
                        rankingSubjectId = id,
                        externalId = candidate.externalId,
                        sourceType = candidate.sourceType,
                        title = candidate.title.trim(),
                        artist = candidate.artist.trim(),
                        album = candidate.album,
                        artworkUrl = candidate.artworkUrl,
                        createdAt = nextId.toLong()
                    )
                    inserted += 1
                }
            }
        return inserted
    }

    fun titleFor(songId: String): String =
        songs.value.first { it.id == songId }.title

    /**
     * Mirrors DefaultImportRepository's restore branch: inserts a fresh SongEntity row
     * pointed at the preserved ranking subject, so the restored song's id is new but its
     * score/wins/losses carry over.
     */
    fun restoreCandidate(candidate: MusicTrackCandidate, rankingSubjectId: String, scoreTenths: Int): String {
        val id = "song-${nextId++}"
        songs.value = songs.value + Song(
            id = id,
            rankingSubjectId = rankingSubjectId,
            externalId = candidate.externalId,
            sourceType = candidate.sourceType,
            title = candidate.title.trim(),
            artist = candidate.artist.trim(),
            album = candidate.album,
            artworkUrl = candidate.artworkUrl,
            createdAt = nextId.toLong(),
            scoreTenths = scoreTenths
        )
        return id
    }
}

private class FakeImportRepository(
    private val songRepository: FakeSongRepository,
    private val importJsonResult: Result<Int> = Result.success(0),
    private val tombstoneMatches: Map<String, TombstoneImportConflict> = emptyMap()
) : ImportRepository {
    val imported = mutableListOf<MusicTrackCandidate>()

    override suspend fun seedSampleSongs(): Result<Int> = Result.success(0)

    override suspend fun importTracks(candidates: List<MusicTrackCandidate>, sourceLabel: String): Result<Int> {
        imported += candidates
        return Result.success(songRepository.importCandidates(candidates))
    }

    override suspend fun importTracks(
        candidates: List<MusicTrackCandidate>,
        sourceLabel: String,
        resolutions: Map<String, TombstoneImportResolution>
    ): Result<Int> {
        imported += candidates
        var count = 0
        candidates.forEach { candidate ->
            val resolution = resolutions[importKey(candidate)]
            if (resolution?.action == TombstoneImportAction.RESTORE) {
                songRepository.restoreCandidate(
                    candidate = candidate,
                    rankingSubjectId = resolution.rankingSubjectId ?: "restored-subject",
                    scoreTenths = 80
                )
                count += 1
            } else {
                count += songRepository.importCandidates(listOf(candidate))
            }
        }
        return Result.success(count)
    }

    override suspend fun findTombstoneMatches(candidates: List<MusicTrackCandidate>): Result<List<TombstoneImportConflict>> =
        Result.success(candidates.mapNotNull { tombstoneMatches[importKey(it)] })

    override suspend fun importFromJson(contentResolver: ContentResolver, uri: Uri): Result<Int> = importJsonResult

    override suspend fun exportToJson(contentResolver: ContentResolver, uri: Uri): Result<Unit> = Result.success(Unit)

    private fun importKey(candidate: MusicTrackCandidate): String =
        "${candidate.sourceType.name}:${candidate.externalId}:${candidate.title.trim().lowercase()}::${candidate.artist.trim().lowercase()}"
}

private class FakeRankingRepository(
    private val saveScoreResult: Result<ScoreSaveResult>? = null
) : RankingRepository {
    val savedScores = mutableListOf<Pair<String, Int>>()

    override fun observeStats(): Flow<AppStats> = flowOf(AppStats())

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> = Result.success(Unit)

    override suspend fun recordSkip(songIds: List<String>): Result<Unit> = Result.success(Unit)

    override suspend fun saveScore(songId: String, scoreTenths: Int): Result<ScoreSaveResult> {
        savedScores += songId to scoreTenths
        return saveScoreResult ?: Result.success(ScoreSaveResult(songId = songId, scoreTenths = scoreTenths))
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

private fun importKeyFor(candidate: MusicTrackCandidate): String =
    "${candidate.sourceType.name}:${candidate.externalId}:${candidate.title.trim().lowercase()}::${candidate.artist.trim().lowercase()}"
