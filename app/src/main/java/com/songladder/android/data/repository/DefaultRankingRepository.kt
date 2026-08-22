package com.songladder.android.data.repository

import androidx.room.withTransaction
import com.songladder.android.data.local.AppStatsDao
import com.songladder.android.data.local.AppStatsEntity
import com.songladder.android.data.local.MatchupEventDao
import com.songladder.android.data.local.MatchupEventEntity
import com.songladder.android.data.local.RankingSubjectDao
import com.songladder.android.data.local.SongDao
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.data.local.SuggestionDismissalDao
import com.songladder.android.data.local.SuggestionDismissalEntity
import com.songladder.android.data.local.toDomain
import com.songladder.android.data.local.toEntity
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.engine.SuggestionEngine
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.DeletedRankingHistory
import com.songladder.android.domain.model.MatchupOutcome
import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.RankingHistoryDeletionResult
import com.songladder.android.domain.model.RankingSubject
import com.songladder.android.domain.model.ResponsivenessEpoch
import com.songladder.android.domain.model.ScoreSaveResult
import com.songladder.android.domain.model.Suggestion
import com.songladder.android.domain.model.TimeSource
import com.songladder.android.domain.model.scoreFirstComparator
import com.songladder.android.domain.model.seedEloForScore
import com.songladder.android.domain.model.validateScoreTenths
import com.songladder.android.domain.repository.RankingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

class DefaultRankingRepository(
    private val database: SongLadderDatabase,
    private val songDao: SongDao,
    private val matchupEngine: EloMatchupEngine,
    private val rankingSubjectDao: RankingSubjectDao,
    private val matchupEventDao: MatchupEventDao,
    private val suggestionDismissalDao: SuggestionDismissalDao,
    private val appStatsDao: AppStatsDao? = null,
    private val timeSource: TimeSource = TimeSource { System.currentTimeMillis() },
    private val suggestionEngine: SuggestionEngine = SuggestionEngine(matchupEngine),
    private val repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : RankingRepository {
    override fun observeStats(): Flow<AppStats> {
        val dao = requireNotNull(appStatsDao) { "AppStatsDao is required for observeStats." }
        return dao.observeAppStats().map { it.toDomain() }
    }

    override fun observeMatchupEvents(): Flow<List<MatchupEvent>> =
        matchupEventDao.observeAll().map { events -> events.map { it.toDomain() } }

    override fun observeDeletedRankingHistories(): Flow<List<DeletedRankingHistory>> =
        combine(rankingSubjectDao.observeDeleted(), matchupEventDao.observeAll()) { subjects, events ->
            subjects.map { subject ->
                val eventCount = events.count {
                    it.firstSubjectId == subject.id || it.secondSubjectId == subject.id
                }
                DeletedRankingHistory(
                    rankingSubjectId = subject.id,
                    title = subject.normalizedTitle,
                    artist = subject.normalizedArtist,
                    scoreTenths = subject.tombstoneScoreTenths ?: subject.scoreTenths,
                    deletedAt = subject.tombstoneDeletedAt ?: 0L,
                    eventCount = eventCount
                )
            }
        }

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> = runCatching {
        database.withTransaction {
            require(winnerId != loserId) { "A matchup requires two distinct songs." }
            val winnerSubject = songDao.getSongWithStats(winnerId)?.stats
                ?: error("Winner subject not found.")
            val loserSubject = songDao.getSongWithStats(loserId)?.stats
                ?: error("Loser subject not found.")
            val update = matchupEngine.updateRatings(
                winner = winnerSubject.toDomain(),
                loser = loserSubject.toDomain(),
                ratedAt = nextEventTimestamp()
            )
            matchupEventDao.insert(
                MatchupEventEntity(
                    sequenceId = matchupEventDao.nextSequenceId(),
                    occurredAt = update.ratedAt,
                    firstSubjectId = winnerSubject.id,
                    secondSubjectId = loserSubject.id,
                    outcome = MatchupOutcome.WIN.name,
                    winnerSubjectId = winnerSubject.id,
                    loserSubjectId = loserSubject.id,
                    winnerEffectiveK = update.winnerEffectiveK,
                    loserEffectiveK = update.loserEffectiveK
                )
            )
            rebuildCaches()
        }
    }

    override suspend fun recordSkip(songIds: List<String>): Result<Unit> = runCatching {
        database.withTransaction {
            val uniqueSongIds = songIds.distinct()
            require(uniqueSongIds.size == 2) { "A skipped matchup requires two distinct songs." }
            val subjects = uniqueSongIds.map { songId ->
                songDao.getSongWithStats(songId)?.stats ?: error("Song not found.")
            }
            matchupEventDao.insert(
                MatchupEventEntity(
                    sequenceId = matchupEventDao.nextSequenceId(),
                    occurredAt = nextEventTimestamp(),
                    firstSubjectId = subjects[0].id,
                    secondSubjectId = subjects[1].id,
                    outcome = MatchupOutcome.SKIP.name
                )
            )
            rebuildCaches()
        }
    }

    override suspend fun saveScore(songId: String, scoreTenths: Int): Result<ScoreSaveResult> = runCatching {
        validateScoreTenths(scoreTenths)
        database.withTransaction {
            val row = songDao.getSongWithStats(songId) ?: error("Song not found.")
            require(row.stats.tombstoneDeletedAt == null) { "Cannot score a deleted song." }
            applyScore(resultId = songId, current = row.stats.toDomain(), scoreTenths = scoreTenths)
        }
    }

    /**
     * Writes [scoreTenths] onto [current] (if changed) and always checkpoints the
     * suggestion dismissal: accepting a suggestion at the subject's current score
     * must still dismiss it, or the identical suggestion reappears on the next
     * recomputation. Does not rebuild elo caches - callers are responsible for
     * calling [rebuildCaches] once after writing all scores in a batch.
     */
    private suspend fun writeScore(current: RankingSubject, scoreTenths: Int, epochSequence: Long): Boolean {
        val changed = current.scoreTenths != scoreTenths
        if (changed) {
            rankingSubjectDao.update(
                current.copy(
                    scoreTenths = scoreTenths,
                    elo = seedEloForScore(scoreTenths),
                    responsivenessEpoch = if (current.scoreTenths == null) {
                        ResponsivenessEpoch.NEW
                    } else {
                        ResponsivenessEpoch.EDITED
                    },
                    completedMatchupsInEpoch = 0,
                    responsivenessEpochSequence = epochSequence,
                    lastRatedAt = nextEventTimestamp()
                ).toEntity()
            )
        }
        suggestionDismissalDao.upsert(
            SuggestionDismissalEntity(
                subjectId = current.id,
                dismissedAtSequenceId = epochSequence,
                dismissedScoreTenths = scoreTenths
            )
        )
        return changed
    }

    /**
     * Shared by [saveScore] (looked up by song id) and [acceptSuggestion]
     * (looked up by ranking subject id, which is not always the same id -
     * see the tombstone-restore matching flow). [resultId] echoes back
     * whichever id the caller used, matching [saveScore]'s existing contract.
     *
     * Reseeding elo from the new score and replaying the full event history
     * generally does NOT reproduce the exact value just set - Elo replay is
     * path-dependent, so a different seed produces a different trajectory.
     * Without the checkpoint write in [writeScore], that reseed artifact alone
     * could make [SuggestionEngine] immediately re-flag this subject as
     * "disagreeing" with the score the user just confirmed, with zero new
     * comparisons. The checkpoint records this confirmation so the engine's
     * existing dismissal gate (new evidence + material movement) applies
     * here too - the artifact from this replay isn't new evidence.
     */
    private suspend fun applyScore(resultId: String, current: RankingSubject, scoreTenths: Int): ScoreSaveResult {
        val beforeOrder = currentSongOrder()
        val epochSequence = matchupEventDao.maxSequenceId()
        if (writeScore(current, scoreTenths, epochSequence)) {
            rebuildCaches()
        }
        val afterOrder = currentSongOrder()
        return ScoreSaveResult(
            songId = resultId,
            scoreTenths = scoreTenths,
            visibleOrderChanged = beforeOrder != afterOrder
        )
    }

    override suspend fun undoLastWinner(): Result<Boolean> = runCatching {
        database.withTransaction {
            val latestWinner = matchupEventDao.getAll()
                .lastOrNull { it.outcome == MatchupOutcome.WIN.name }
                ?: return@withTransaction false
            matchupEventDao.delete(latestWinner.sequenceId)
            listOf(latestWinner.firstSubjectId, latestWinner.secondSubjectId).forEach { subjectId ->
                clearDismissalIfSupersededByRemoval(subjectId, latestWinner.sequenceId)
            }
            rebuildCaches()
            true
        }
    }

    /**
     * A dismissal checkpoint must not outlive the event(s) it was checkpointed
     * against. Clears [subjectId]'s checkpoint only if it was recorded at or
     * after [removedSequenceId] - an older checkpoint predates the removed
     * event entirely and is unrelated to it, so it must stay valid.
     */
    private suspend fun clearDismissalIfSupersededByRemoval(subjectId: String, removedSequenceId: Long) {
        val dismissal = suggestionDismissalDao.get(subjectId)
        if (dismissal != null && dismissal.dismissedAtSequenceId >= removedSequenceId) {
            suggestionDismissalDao.delete(subjectId)
        }
    }

    override suspend fun deleteRankingHistory(rankingSubjectId: String): Result<RankingHistoryDeletionResult> = runCatching {
        database.withTransaction {
            val removedEvents = matchupEventDao.getAll().filter {
                it.firstSubjectId == rankingSubjectId || it.secondSubjectId == rankingSubjectId
            }
            matchupEventDao.deleteForSubject(rankingSubjectId)
            suggestionDismissalDao.delete(rankingSubjectId)
            removedEvents.forEach { event ->
                val opponentId = if (event.firstSubjectId == rankingSubjectId) {
                    event.secondSubjectId
                } else {
                    event.firstSubjectId
                }
                clearDismissalIfSupersededByRemoval(opponentId, event.sequenceId)
            }
            rebuildCaches()
            RankingHistoryDeletionResult(
                rankingSubjectId = rankingSubjectId,
                deletedEventCount = removedEvents.size
            )
        }
    }

    override suspend fun deleteAllRankingHistory(): Result<RankingHistoryDeletionResult> = runCatching {
        database.withTransaction {
            val deletedEventCount = matchupEventDao.getAll().size
            matchupEventDao.clearAll()
            suggestionDismissalDao.clearAll()
            rebuildCaches()
            RankingHistoryDeletionResult(
                rankingSubjectId = null,
                deletedEventCount = deletedEventCount
            )
        }
    }

    private val suggestionsFlow: Flow<List<Suggestion>> = combine(
        rankingSubjectDao.observeAllIncludingDeleted(),
        matchupEventDao.observeAll(),
        suggestionDismissalDao.observeAll(),
        songDao.observeRankingSubjectIdsWithSong()
    ) { subjectEntities, eventEntities, dismissalEntities, subjectIdsWithSong ->
        // Include tombstoned subjects so events against a deleted opponent still
        // resolve during replay (EloMatchupEngine requires both sides of every
        // event to be present) instead of silently shrinking a still-active
        // subject's win-event history once an old opponent is deleted.
        // SuggestionEngine itself skips tombstoned subjects when emitting suggestions.
        val subjects = subjectEntities.map { it.toDomain() }
        val events = eventEntities.map { it.toDomain() }
        val songSubjectIds = subjectIdsWithSong.toSet()
        // A subject with no matching song row (e.g. from a malformed import) can
        // never be accepted - filter it out here so it never surfaces as a
        // dead-end suggestion the user can't act on.
        suggestionEngine.computeSuggestions(
            subjects = subjects,
            events = events,
            dismissals = dismissalEntities.map { it.toDomain() }
        ).filter { it.subjectId in songSubjectIds }
    }.shareIn(repositoryScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    override fun observeSuggestions(): Flow<List<Suggestion>> = suggestionsFlow

    /**
     * Shared by [acceptSuggestion] and [acceptSuggestions]: resolves the active
     * (non-tombstoned) subject for [subjectId] and the song id it maps to.
     */
    private suspend fun resolveActiveSongId(subjectId: String): Pair<RankingSubject, String> {
        val subject = rankingSubjectDao.get(subjectId) ?: error("Ranking subject not found.")
        require(subject.tombstoneDeletedAt == null) { "Cannot score a deleted song." }
        val songId = songDao.getSongIdForRankingSubject(subjectId) ?: error("Song not found for ranking subject.")
        return subject.toDomain() to songId
    }

    override suspend fun acceptSuggestion(subjectId: String, scoreTenths: Int): Result<ScoreSaveResult> = runCatching {
        validateScoreTenths(scoreTenths)
        database.withTransaction {
            val (subject, songId) = resolveActiveSongId(subjectId)
            applyScore(resultId = songId, current = subject, scoreTenths = scoreTenths)
        }
    }

    override suspend fun acceptSuggestions(accepts: List<Pair<String, Int>>): Result<List<ScoreSaveResult>> = runCatching {
        if (accepts.isEmpty()) return@runCatching emptyList()
        database.withTransaction {
            val beforeOrder = currentSongOrder()
            val epochSequence = matchupEventDao.maxSequenceId()
            var anyChanged = false
            val songIds = accepts.map { (subjectId, scoreTenths) ->
                validateScoreTenths(scoreTenths)
                val (subject, songId) = resolveActiveSongId(subjectId)
                if (writeScore(subject, scoreTenths, epochSequence)) {
                    anyChanged = true
                }
                songId
            }
            if (anyChanged) {
                rebuildCaches()
            }
            val afterOrder = currentSongOrder()
            val visibleOrderChanged = beforeOrder != afterOrder
            accepts.zip(songIds).map { (accept, songId) ->
                ScoreSaveResult(songId = songId, scoreTenths = accept.second, visibleOrderChanged = visibleOrderChanged)
            }
        }
    }

    override suspend fun dismissSuggestionLater(
        subjectId: String,
        suggestedScoreTenths: Int,
        lastEventSequenceId: Long
    ): Result<Unit> = runCatching {
        suggestionDismissalDao.upsert(
            SuggestionDismissalEntity(
                subjectId = subjectId,
                dismissedAtSequenceId = lastEventSequenceId,
                dismissedScoreTenths = suggestedScoreTenths
            )
        )
    }

    private suspend fun rebuildCaches(): List<RankingSubject> {
        val subjects = rankingSubjectDao.getAll().map { it.toDomain() }
        val events = matchupEventDao.getAll().map { it.toDomain() }
        val replayed = matchupEngine.replay(subjects, events)
        replayed.forEach { rankingSubjectDao.update(it.toEntity()) }
        appStatsDao?.upsert(
            AppStatsEntity(
                matchCount = events.count { it.outcome == MatchupOutcome.WIN },
                skipCount = events.count { it.outcome == MatchupOutcome.SKIP }
            )
        )
        return replayed
    }

    private suspend fun currentSongOrder(): List<String> = songDao.getSongsWithStats()
        .map { it.toDomain() }
        .sortedWith(scoreFirstComparator())
        .map { it.id }

    private suspend fun nextEventTimestamp(): Long {
        val latest = matchupEventDao.getAll().maxOfOrNull { it.occurredAt } ?: Long.MIN_VALUE
        return maxOf(timeSource.now(), latest + 1L)
    }
}
