package com.songladder.android.domain.repository

import android.content.ContentResolver
import android.net.Uri
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import kotlinx.coroutines.flow.Flow

interface SongRepository {
    fun observeSongs(): Flow<List<Song>>
    suspend fun addSong(input: SongInput): Result<Unit>
    suspend fun removeSong(songId: String)
    suspend fun resetLibrary()
}

interface RankingRepository {
    fun observeStats(): Flow<AppStats>
    suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit>
    suspend fun recordSkip(songId: String)
}

interface ImportRepository {
    suspend fun seedSampleSongs()
    suspend fun importTracks(candidates: List<MusicTrackCandidate>, sourceLabel: String): Result<Int>
    suspend fun importFromJson(contentResolver: ContentResolver, uri: Uri): Result<Int>
    suspend fun exportToJson(contentResolver: ContentResolver, uri: Uri): Result<Unit>
}

interface MusicSourceClient {
    suspend fun searchTracks(query: String, authToken: String): Result<List<MusicTrackCandidate>>
}
