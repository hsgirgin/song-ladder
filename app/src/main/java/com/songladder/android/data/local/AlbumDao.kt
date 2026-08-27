package com.songladder.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums")
    fun observeAll(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums")
    suspend fun getAll(): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id = :albumId")
    suspend fun get(albumId: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE id = :albumId")
    fun observe(albumId: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums WHERE matchStatus = :matchStatus")
    suspend fun getByMatchStatus(matchStatus: String): List<AlbumEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(album: AlbumEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(albums: List<AlbumEntity>)

    @Query("DELETE FROM albums")
    suspend fun clearAll()
}

@Dao
interface AlbumTrackExclusionDao {
    @Query("SELECT * FROM album_track_exclusions")
    fun observeAll(): Flow<List<AlbumTrackExclusionEntity>>

    @Query("SELECT * FROM album_track_exclusions")
    suspend fun getAll(): List<AlbumTrackExclusionEntity>

    @Query("SELECT * FROM album_track_exclusions WHERE albumId = :albumId")
    suspend fun getForAlbum(albumId: String): List<AlbumTrackExclusionEntity>

    @Query("SELECT * FROM album_track_exclusions WHERE songId = :songId")
    suspend fun get(songId: String): AlbumTrackExclusionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(exclusion: AlbumTrackExclusionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(exclusions: List<AlbumTrackExclusionEntity>)

    @Query("DELETE FROM album_track_exclusions WHERE songId = :songId")
    suspend fun delete(songId: String)

    @Query("DELETE FROM album_track_exclusions")
    suspend fun clearAll()
}

@Dao
interface AlbumMissingTrackDao {
    @Query("SELECT * FROM album_missing_tracks WHERE albumId = :albumId")
    fun observeForAlbum(albumId: String): Flow<List<AlbumMissingTrackEntity>>

    @Query("SELECT * FROM album_missing_tracks WHERE albumId = :albumId")
    suspend fun getForAlbum(albumId: String): List<AlbumMissingTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<AlbumMissingTrackEntity>)

    @Query("DELETE FROM album_missing_tracks WHERE albumId = :albumId")
    suspend fun clearForAlbum(albumId: String)

    @Query("DELETE FROM album_missing_tracks WHERE albumId = :albumId AND providerTrackId IN (:providerTrackIds)")
    suspend fun delete(albumId: String, providerTrackIds: List<String>)

    @Query("DELETE FROM album_missing_tracks")
    suspend fun clearAll()
}
