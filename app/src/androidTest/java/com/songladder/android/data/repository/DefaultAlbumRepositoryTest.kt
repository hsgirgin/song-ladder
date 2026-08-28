package com.songladder.android.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.songladder.android.data.local.AlbumEntity
import com.songladder.android.data.local.RankingSubjectEntity
import com.songladder.android.data.local.SongEntity
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.domain.model.AlbumMatchStatus
import com.songladder.android.domain.model.AlbumReleaseCandidate
import com.songladder.android.domain.model.AlbumReleaseLookup
import com.songladder.android.domain.model.AlbumReleaseTrack
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.TimeSource
import com.songladder.android.domain.repository.AlbumMetadataProvider
import com.songladder.android.domain.repository.AlbumMetadataUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DefaultAlbumRepositoryTest {
    private lateinit var database: SongLadderDatabase
    private lateinit var settingsRepository: DefaultSettingsRepository
    private var clockMillis = 1_000_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, SongLadderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsRepository = DefaultSettingsRepository(database.rankingSettingsDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun autoDiscoversAndAutoMatchesANewAlbumGroupingFromInsertedSongs() = runBlocking {
        val provider = FakeAlbumMetadataProvider(
            searchResult = Result.success(
                listOf(
                    AlbumReleaseCandidate(
                        collectionId = "collection-1",
                        collectionName = "Blonde",
                        artistName = "Frank Ocean",
                        trackCount = 2
                    )
                )
            ),
            lookupResults = mutableMapOf(
                "collection-1" to Result.success(
                    AlbumReleaseLookup(
                        collectionId = "collection-1",
                        collectionName = "Blonde",
                        artistName = "Frank Ocean",
                        trackCount = 2,
                        tracks = listOf(
                            AlbumReleaseTrack(trackId = "t1", title = "Nikes", trackNumber = 1),
                            AlbumReleaseTrack(trackId = "t2", title = "Ivy", trackNumber = 2)
                        )
                    )
                )
            )
        )
        val repository = repository(provider)

        insertSong(id = "song-1", title = "Nikes", artist = "Frank Ocean", album = "Blonde")

        val album = waitForAlbum { it.matchStatus == AlbumMatchStatus.AUTO_MATCHED.name }
        assertEquals("collection-1", album.providerCollectionId)
        assertEquals(2, album.providerTrackCount)

        // The release-track write happens in a second transaction after the match
        // status commits, so wait for it rather than assuming it's already there the
        // instant matchStatus flips.
        val missing = waitForMissingTracks(repository, album.id) { it.isNotEmpty() }
        assertEquals(listOf("Ivy"), missing.map { it.title })
    }

    @Test
    fun autoMatchLeavesAlbumPendingWhenTheProviderIsUnavailable() = runBlocking {
        val provider = FakeAlbumMetadataProvider(
            searchResult = Result.failure(AlbumMetadataUnavailableException("rate limited"))
        )
        val repository = repository(provider)

        insertSong(id = "song-1", title = "Nikes", artist = "Frank Ocean", album = "Blonde")

        val album = waitForAlbum { it.lastMatchAttemptAt != null }
        assertEquals(AlbumMatchStatus.PENDING.name, album.matchStatus)
        assertNull(album.providerCollectionId)
    }

    @Test
    fun autoMatchLeavesAlbumPendingWhenTheReleaseLookupFails() = runBlocking {
        // The search half of matching succeeds with a confident candidate, but the
        // follow-up release lookup that fills in artwork/missing-tracks fails (no
        // fixture registered for its collection id, matching FakeAlbumMetadataProvider
        // 's default "unavailable" behavior). This must not be committed as a "done"
        // AUTO_MATCHED album with no artwork/tracks and no way back to PENDING - see
        // https://github.com/hsgirgin/song-ladder/issues/65.
        val provider = FakeAlbumMetadataProvider(
            searchResult = Result.success(
                listOf(
                    AlbumReleaseCandidate(
                        collectionId = "collection-1",
                        collectionName = "Blonde",
                        artistName = "Frank Ocean",
                        trackCount = 2
                    )
                )
            )
        )
        val repository = repository(provider)

        insertSong(id = "song-1", title = "Nikes", artist = "Frank Ocean", album = "Blonde")

        val album = waitForAlbum { it.lastMatchAttemptAt != null }
        assertEquals(AlbumMatchStatus.PENDING.name, album.matchStatus)
        assertNull(album.providerCollectionId)
    }

    @Test
    fun setTrackExcludedIsTransactionalAndRecomputesTheAverage() = runBlocking {
        val provider = FakeAlbumMetadataProvider()
        val repository = repository(provider)
        insertSong(id = "song-1", title = "Nikes", artist = "Frank Ocean", album = "Blonde", scoreTenths = 90)
        insertSong(id = "song-2", title = "Ivy", artist = "Frank Ocean", album = "Blonde", scoreTenths = 70)
        val albumId = waitForAlbum { true }.id
        // Force a known, matched track count so the score threshold is satisfied
        // regardless of the fake provider's (empty) search results.
        confirmAlbum(albumId, trackCount = 2)

        val beforeExclusion = repository.observeAlbums().first().single { it.album.id == albumId }
        assertEquals(80, beforeExclusion.scoreTenths)

        repository.setTrackExcluded(albumId, "song-2", excluded = true).getOrThrow()
        val afterExclusion = repository.observeAlbums().first().single { it.album.id == albumId }
        assertEquals(90, afterExclusion.scoreTenths)
        assertEquals(1, afterExclusion.includedRatedTrackCount)

        repository.setTrackExcluded(albumId, "song-2", excluded = false).getOrThrow()
        val afterUnexclusion = repository.observeAlbums().first().single { it.album.id == albumId }
        assertEquals(80, afterUnexclusion.scoreTenths)
    }

    @Test
    fun chooseReleaseConfirmsAndSurvivesALaterAutoMatchPass() = runBlocking {
        val provider = FakeAlbumMetadataProvider(
            lookupResults = mutableMapOf(
                "chosen-collection" to Result.success(
                    AlbumReleaseLookup(
                        collectionId = "chosen-collection",
                        collectionName = "Blonde",
                        artistName = "Frank Ocean",
                        trackCount = 1,
                        tracks = listOf(AlbumReleaseTrack(trackId = "t1", title = "Nikes", trackNumber = 1))
                    )
                )
            )
        )
        val repository = repository(provider)
        insertSong(id = "song-1", title = "Nikes", artist = "Frank Ocean", album = "Blonde")
        val albumId = waitForAlbum { true }.id

        repository.chooseRelease(albumId, "chosen-collection").getOrThrow()
        val confirmed = database.albumDao().get(albumId)
        assertEquals(AlbumMatchStatus.CONFIRMED.name, confirmed?.matchStatus)
        assertEquals("chosen-collection", confirmed?.providerCollectionId)

        // A later song-list change re-runs discoverAndMatch, but only PENDING albums
        // are ever auto-(re)matched - a CONFIRMED album must not be overwritten. Using
        // a second track on the *same* grouping wouldn't actually prove this: the
        // derived groupings list would be structurally unchanged, so
        // distinctUntilChanged would suppress the re-emission and no match pass would
        // run at all. Insert a song under a *different* grouping instead, so a real
        // discoverAndMatch pass is guaranteed to run (and, per the invariant under
        // test, must skip the already-CONFIRMED album).
        insertSong(id = "song-2", title = "Get Lucky", artist = "Daft Punk", album = "Random Access Memories")
        waitForAlbum { it.title == "Random Access Memories" && it.matchStatus == AlbumMatchStatus.NO_MATCH.name }

        val stillConfirmed = database.albumDao().get(albumId)
        assertEquals(AlbumMatchStatus.CONFIRMED.name, stillConfirmed?.matchStatus)
        assertEquals("chosen-collection", stillConfirmed?.providerCollectionId)
    }

    @Test
    fun concurrentRetryPassesNeverDoubleMatchTheSameAlbum() = runBlocking {
        // Every search call fails (leaving the album PENDING forever, exactly like
        // autoMatchLeavesAlbumPendingWhenTheProviderIsUnavailable) and is
        // artificially delayed, so there's a real window for two overlapping calls to
        // race - and so provider.searchCalls stays a reliable count regardless of how
        // many times matching is retried.
        val provider = FakeAlbumMetadataProvider(
            searchResult = Result.failure(AlbumMetadataUnavailableException("rate limited")),
            searchDelayMillis = 150
        )
        val repository = repository(provider)
        insertSong(id = "song-1", title = "Nikes", artist = "Frank Ocean", album = "Blonde")
        waitForAlbum { it.lastMatchAttemptAt != null }
        // lastMatchAttemptAt is written before the (delayed) search call, so wait out
        // the delay to let the initial auto-discovery attempt actually finish and
        // release the in-flight guard before manipulating state below.
        delay(300)
        val searchCallsBeforeRetry = provider.searchCalls.size

        // Rewind the backoff so the album looks eligible for another attempt to both
        // concurrent callers, the same way a flaky-wifi burst of connectivity
        // callbacks could each see it as eligible before either one's status write
        // commits. Run on a real multi-threaded dispatcher (not the repository's own
        // Unconfined test scope) so the two calls can genuinely overlap.
        val album = database.albumDao().getAll().single()
        database.albumDao().insert(album.copy(lastMatchAttemptAt = null))

        val first = async(Dispatchers.Default) { repository.retryPendingMatches() }
        val second = async(Dispatchers.Default) { repository.retryPendingMatches() }
        first.await().getOrThrow()
        second.await().getOrThrow()

        assertEquals(searchCallsBeforeRetry + 1, provider.searchCalls.size)
    }

    @Test
    fun refreshMetadataReRunsTheMatcherForAConfirmedAlbum() = runBlocking {
        val provider = FakeAlbumMetadataProvider(
            lookupResults = mutableMapOf(
                "collection-1" to Result.success(
                    AlbumReleaseLookup(
                        collectionId = "collection-1",
                        collectionName = "Blonde",
                        artistName = "Frank Ocean",
                        trackCount = 2,
                        tracks = listOf(
                            AlbumReleaseTrack(trackId = "t1", title = "Nikes", trackNumber = 1),
                            AlbumReleaseTrack(trackId = "t2", title = "Ivy", trackNumber = 2)
                        )
                    )
                )
            )
        )
        val repository = repository(provider)
        insertSong(id = "song-1", title = "Nikes", artist = "Frank Ocean", album = "Blonde")
        val albumId = waitForAlbum { true }.id
        confirmAlbum(albumId, collectionId = "collection-1", trackCount = 1)

        repository.refreshMetadata(albumId).getOrThrow()

        val refreshed = database.albumDao().get(albumId)
        assertEquals(2, refreshed?.providerTrackCount)
        // The full release tracklist is persisted now, not just what's missing - Nikes
        // (owned) and Ivy (not) both land in the table; only observeAlbumDetail derives
        // the missing subset.
        val releaseTracks = database.albumReleaseTrackDao().getForAlbum(albumId)
        assertEquals(setOf("Nikes", "Ivy"), releaseTracks.map { it.title }.toSet())
        val detail = repository.observeAlbumDetail(albumId).first()
        assertEquals(listOf("Ivy"), detail?.missingTracks?.map { it.title })
    }

    @Test
    fun retryPendingMatchesOnlyRunsWhenMetadataRetrievalIsEnabled() = runBlocking {
        val provider = FakeAlbumMetadataProvider(searchResult = Result.success(emptyList()))
        val repository = repository(provider)
        settingsRepository.saveSettings(RankingSettings(metadataRetrievalEnabled = false)).getOrThrow()

        insertSong(id = "song-1", title = "Nikes", artist = "Frank Ocean", album = "Blonde")
        delay(200)
        val stillUntouched = database.albumDao().getAll().single()
        assertNull(stillUntouched.lastMatchAttemptAt)

        repository.retryPendingMatches().getOrThrow()
        assertNull(database.albumDao().getAll().single().lastMatchAttemptAt)

        settingsRepository.saveSettings(RankingSettings(metadataRetrievalEnabled = true)).getOrThrow()
        repository.retryPendingMatches().getOrThrow()
        val attempted = database.albumDao().getAll().single()
        assertNotNull(attempted.lastMatchAttemptAt)
    }

    @Test
    fun searchReleaseCandidatesDelegatesToTheProviderForTheAlbumsArtistAndTitle() = runBlocking {
        val provider = FakeAlbumMetadataProvider(
            searchResult = Result.success(
                listOf(
                    AlbumReleaseCandidate(collectionId = "collection-1", collectionName = "Blonde", artistName = "Frank Ocean", trackCount = 2),
                    AlbumReleaseCandidate(collectionId = "collection-2", collectionName = "Blond", artistName = "Frank Ocean", trackCount = 1)
                )
            )
        )
        val repository = repository(provider)
        insertSong(id = "song-1", title = "Nikes", artist = "Frank Ocean", album = "Blonde")
        val album = waitForAlbum { true }
        provider.searchCalls.clear()

        val candidates = repository.searchReleaseCandidates(album.id).getOrThrow()

        assertEquals(listOf("Frank Ocean" to "Blonde"), provider.searchCalls)
        assertEquals(listOf("collection-1", "collection-2"), candidates.map { it.collectionId })
    }

    private fun repository(provider: AlbumMetadataProvider) = DefaultAlbumRepository(
        database = database,
        songDao = database.songDao(),
        albumDao = database.albumDao(),
        albumTrackExclusionDao = database.albumTrackExclusionDao(),
        albumReleaseTrackDao = database.albumReleaseTrackDao(),
        albumMetadataProvider = provider,
        settingsRepository = settingsRepository,
        timeSource = TimeSource { clockMillis++ },
        repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    private suspend fun insertSong(
        id: String,
        title: String,
        artist: String,
        album: String,
        scoreTenths: Int? = null
    ) {
        database.songDao().insertSongWithStats(
            song = SongEntity(
                id = id,
                rankingSubjectId = id,
                sourceType = "ITUNES",
                title = title,
                artist = artist,
                album = album,
                createdAt = clockMillis++
            ),
            stats = RankingSubjectEntity(
                id = id,
                scoreTenths = scoreTenths,
                normalizedTitle = title.trim().lowercase(),
                normalizedArtist = artist.trim().lowercase()
            )
        )
    }

    private suspend fun confirmAlbum(albumId: String, collectionId: String = "forced-collection", trackCount: Int) {
        val album = database.albumDao().get(albumId) ?: error("Album not found in test setup.")
        database.albumDao().insert(
            album.copy(
                providerCollectionId = collectionId,
                providerTrackCount = trackCount,
                matchStatus = AlbumMatchStatus.CONFIRMED.name
            )
        )
    }

    private suspend fun waitForAlbum(
        timeoutMillis: Long = 5_000,
        predicate: (AlbumEntity) -> Boolean
    ): AlbumEntity {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            database.albumDao().getAll().firstOrNull(predicate)?.let { return it }
            delay(20)
        }
        error("No matching album appeared within ${timeoutMillis}ms")
    }

    private suspend fun waitForMissingTracks(
        repository: DefaultAlbumRepository,
        albumId: String,
        timeoutMillis: Long = 5_000,
        predicate: (List<AlbumReleaseTrack>) -> Boolean
    ): List<AlbumReleaseTrack> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val current = repository.observeAlbumDetail(albumId).first()?.missingTracks.orEmpty()
            if (predicate(current)) return current
            delay(20)
        }
        error("Missing tracks condition not met within ${timeoutMillis}ms")
    }
}

private class FakeAlbumMetadataProvider(
    private val searchResult: Result<List<AlbumReleaseCandidate>> = Result.success(emptyList()),
    private val lookupResults: MutableMap<String, Result<AlbumReleaseLookup>> = mutableMapOf(),
    private val searchDelayMillis: Long = 0L
) : AlbumMetadataProvider {
    val searchCalls = mutableListOf<Pair<String, String>>()

    override suspend fun searchReleases(artist: String, album: String): Result<List<AlbumReleaseCandidate>> {
        searchCalls += artist to album
        if (searchDelayMillis > 0) delay(searchDelayMillis)
        return searchResult
    }

    override suspend fun lookupRelease(collectionId: String, forceRefresh: Boolean): Result<AlbumReleaseLookup> =
        lookupResults[collectionId]
            ?: Result.failure(AlbumMetadataUnavailableException("No fixture for $collectionId"))
}
