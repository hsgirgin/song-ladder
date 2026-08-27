package com.songladder.android.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumDaoTest {
    private lateinit var database: SongLadderDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, SongLadderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndGetAlbumRoundTrips() = runBlocking {
        val albumDao = database.albumDao()
        val album = AlbumEntity(
            id = "frank ocean::blonde",
            title = "Blonde",
            artist = "Frank Ocean",
            normalizedTitle = "blonde",
            normalizedArtist = "frank ocean",
            providerCollectionId = "collection-1",
            providerTrackCount = 17,
            matchStatus = "AUTO_MATCHED",
            matchConfidence = 0.9,
            createdAt = 1000L
        )

        albumDao.insert(album)

        assertEquals(album, albumDao.get("frank ocean::blonde"))
        assertEquals(listOf(album), albumDao.getAll())
        assertEquals(listOf(album), albumDao.observeAll().first())
        assertEquals(listOf(album), albumDao.getByMatchStatus("AUTO_MATCHED"))
        assertTrue(albumDao.getByMatchStatus("PENDING").isEmpty())
    }

    @Test
    fun insertReplacesAlbumWithSameId() = runBlocking {
        val albumDao = database.albumDao()
        val original = AlbumEntity(
            id = "album-1",
            title = "Blonde",
            artist = "Frank Ocean",
            matchStatus = "PENDING",
            createdAt = 1000L
        )
        albumDao.insert(original)
        albumDao.insert(original.copy(matchStatus = "CONFIRMED", providerCollectionId = "c-1"))

        val stored = albumDao.get("album-1")
        assertEquals("CONFIRMED", stored?.matchStatus)
        assertEquals("c-1", stored?.providerCollectionId)
    }

    @Test
    fun clearAllRemovesAlbums() = runBlocking {
        val albumDao = database.albumDao()
        albumDao.insertAll(
            listOf(
                AlbumEntity(id = "album-1", title = "Blonde", artist = "Frank Ocean", createdAt = 1L),
                AlbumEntity(id = "album-2", title = "channel ORANGE", artist = "Frank Ocean", createdAt = 2L)
            )
        )

        albumDao.clearAll()

        assertTrue(albumDao.getAll().isEmpty())
    }

    @Test
    fun trackExclusionCrudScopesByAlbumAndSong() = runBlocking {
        val exclusionDao = database.albumTrackExclusionDao()
        val exclusion = AlbumTrackExclusionEntity(
            songId = "song-1",
            albumId = "album-1",
            excludedAt = 5000L
        )

        exclusionDao.insert(exclusion)

        assertEquals(exclusion, exclusionDao.get("song-1"))
        assertEquals(listOf(exclusion), exclusionDao.getForAlbum("album-1"))
        assertTrue(exclusionDao.getForAlbum("album-2").isEmpty())
        assertEquals(listOf(exclusion), exclusionDao.observeAll().first())

        exclusionDao.delete("song-1")

        assertNull(exclusionDao.get("song-1"))
        assertTrue(exclusionDao.getForAlbum("album-1").isEmpty())
    }

    @Test
    fun missingTrackCrudScopesByAlbumAndSupportsRebuild() = runBlocking {
        val missingTrackDao = database.albumMissingTrackDao()
        val trackOne = AlbumMissingTrackEntity(
            albumId = "album-1",
            providerTrackId = "track-1",
            title = "Ivy",
            trackNumber = 3
        )
        val trackTwo = AlbumMissingTrackEntity(
            albumId = "album-1",
            providerTrackId = "track-2",
            title = "Pink + White",
            trackNumber = 4
        )

        missingTrackDao.insertAll(listOf(trackOne, trackTwo))
        assertEquals(listOf(trackOne, trackTwo), missingTrackDao.getForAlbum("album-1"))
        assertEquals(listOf(trackOne, trackTwo), missingTrackDao.observeForAlbum("album-1").first())

        // Simulate a re-match rebuild: clear this album's rows and insert a fresh set,
        // without disturbing another album's missing tracks.
        val otherAlbumTrack = AlbumMissingTrackEntity(
            albumId = "album-2",
            providerTrackId = "track-3",
            title = "Thinkin Bout You"
        )
        missingTrackDao.insertAll(listOf(otherAlbumTrack))

        missingTrackDao.clearForAlbum("album-1")
        missingTrackDao.insertAll(listOf(trackOne))

        assertEquals(listOf(trackOne), missingTrackDao.getForAlbum("album-1"))
        assertEquals(listOf(otherAlbumTrack), missingTrackDao.getForAlbum("album-2"))
    }
}
