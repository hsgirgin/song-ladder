package com.songladder.android.domain.repository

import android.content.ContentResolver
import android.net.Uri
import com.songladder.android.domain.model.AlbumDetail
import com.songladder.android.domain.model.AlbumReleaseCandidate
import com.songladder.android.domain.model.AlbumReleaseLookup
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.DeletedRankingHistory
import com.songladder.android.domain.model.PlaylistImportPreview
import com.songladder.android.domain.model.RankedAlbum
import com.songladder.android.domain.model.RankingHistoryDeletionResult
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.ScoreSaveResult
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.model.Suggestion
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

    suspend fun findAmbiguousMatches(candidate: MusicTrackCandidate): Result<List<Song>> =
        Result.success(emptyList())
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

    fun observeSuggestions(): Flow<List<Suggestion>> = flowOf(emptyList())

    suspend fun acceptSuggestion(subjectId: String, scoreTenths: Int): Result<ScoreSaveResult> =
        Result.failure(UnsupportedOperationException("Suggestions are not available yet."))

    suspend fun acceptSuggestions(accepts: List<Pair<String, Int>>): Result<List<ScoreSaveResult>> = runCatching {
        accepts.map { (subjectId, scoreTenths) -> acceptSuggestion(subjectId, scoreTenths).getOrThrow() }
    }

    suspend fun dismissSuggestionLater(
        subjectId: String,
        suggestedScoreTenths: Int,
        lastEventSequenceId: Long
    ): Result<Unit> =
        Result.failure(UnsupportedOperationException("Suggestions are not available yet."))
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

interface AlbumMetadataProvider {
    suspend fun searchReleases(artist: String, album: String): Result<List<AlbumReleaseCandidate>>

    /**
     * [forceRefresh] bypasses the provider's own TTL cache. Only an explicit
     * "Refresh metadata" action should pass true - every other caller (auto-match,
     * an explicit release choice) should accept a recent cached lookup rather than
     * spending another network round trip, so refresh remains the one action that's
     * actually guaranteed to re-fetch.
     */
    suspend fun lookupRelease(collectionId: String, forceRefresh: Boolean = false): Result<AlbumReleaseLookup>
}

/**
 * Raised by an [AlbumMetadataProvider] when the provider itself couldn't be reached
 * (network failure, timeout, rate limiting, non-2xx) - distinct from a successful
 * response that simply contains no good candidates. [DefaultAlbumRepository] uses this
 * distinction to leave an album PENDING for a later retry rather than marking it
 * NO_MATCH from an inconclusive network failure.
 */
class AlbumMetadataUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface AlbumRepository {
    fun observeAlbums(): Flow<List<RankedAlbum>>
    fun observeAlbumDetail(albumId: String): Flow<AlbumDetail?>
    suspend fun setTrackExcluded(albumId: String, songId: String, excluded: Boolean): Result<Unit>
    suspend fun chooseRelease(albumId: String, providerCollectionId: String): Result<Unit>
    suspend fun addMissingTracks(albumId: String, providerTrackIds: List<String>): Result<Int>
    suspend fun refreshMetadata(albumId: String): Result<Unit>
    suspend fun retryPendingMatches(): Result<Unit>

    /**
     * Fresh candidate releases for a NEEDS_REVIEW album's inline picker - a direct
     * provider search, not the single best-candidate result [AlbumEntity] persists,
     * since the whole point of the picker is to show the user the runner-ups too.
     */
    suspend fun searchReleaseCandidates(albumId: String): Result<List<AlbumReleaseCandidate>>
}
