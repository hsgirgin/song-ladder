package com.songladder.android.domain.repository

import android.content.ContentResolver
import android.net.Uri
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.DeletedRankingHistory
import com.songladder.android.domain.model.PlaylistImportPreview
import com.songladder.android.domain.model.RankingHistoryDeletionResult
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.ScoreSaveResult
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.TombstoneImportConflict
import com.songladder.android.domain.model.TombstoneImportResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface SongRepository {
    fun observeSongs(): Flow<List<Song>>
    suspend fun addSong(input: SongInput): Result<Unit>
    suspend fun removeSong(songId: String): Result<Unit>
    suspend fun resetLibrary(): Result<Unit>

    suspend fun restoreSong(input: SongInput, rankingSubjectId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Song restoration is not available yet."))
}

interface RankingRepository {
    fun observeStats(): Flow<AppStats>
    fun observeMatchupEvents(): Flow<List<MatchupEvent>> = flowOf(emptyList())
    fun observeDeletedRankingHistories(): Flow<List<DeletedRankingHistory>> = flowOf(emptyList())
    suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit>
    suspend fun recordSkip(songIds: List<String>): Result<Unit>

    suspend fun saveScore(songId: String, scoreTenths: Int): Result<ScoreSaveResult> =
        Result.failure(UnsupportedOperationException("Score saving is not available yet."))

    suspend fun undoLastWinner(): Result<Boolean> =
        Result.failure(UnsupportedOperationException("Undo is not available yet."))

    suspend fun deleteRankingHistory(rankingSubjectId: String): Result<RankingHistoryDeletionResult> =
        Result.failure(UnsupportedOperationException("Ranking history deletion is not available yet."))

    suspend fun deleteAllRankingHistory(): Result<RankingHistoryDeletionResult> =
        Result.failure(UnsupportedOperationException("Ranking history deletion is not available yet."))
}

interface SettingsRepository {
    fun observeSettings(): Flow<RankingSettings>
    suspend fun saveSettings(settings: RankingSettings): Result<Unit>
}

interface ImportRepository {
    suspend fun seedSampleSongs(): Result<Int>
    suspend fun importTracks(candidates: List<MusicTrackCandidate>, sourceLabel: String): Result<Int>
    suspend fun importTracks(
        candidates: List<MusicTrackCandidate>,
        sourceLabel: String,
        resolutions: Map<String, TombstoneImportResolution>
    ): Result<Int> = importTracks(candidates, sourceLabel)

    suspend fun findTombstoneMatches(
        candidates: List<MusicTrackCandidate>
    ): Result<List<TombstoneImportConflict>> = Result.success(emptyList())
    suspend fun importFromJson(contentResolver: ContentResolver, uri: Uri): Result<Int>
    suspend fun exportToJson(contentResolver: ContentResolver, uri: Uri): Result<Unit>
}

interface MusicSourceClient {
    suspend fun searchTracks(query: String): Result<List<MusicTrackCandidate>>
}

interface PlaylistSourceClient {
    suspend fun previewPlaylist(url: String): Result<PlaylistImportPreview>
}
