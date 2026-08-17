package com.songladder.android.data.repository

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.songladder.android.data.local.AppStatsDao
import com.songladder.android.data.local.AppStatsEntity
import com.songladder.android.data.local.ExportEntities
import com.songladder.android.data.local.ImportBatchDao
import com.songladder.android.data.local.ImportBatchEntity
import com.songladder.android.data.local.RankingSettingsDao
import com.songladder.android.data.local.RankingSettingsEntity
import com.songladder.android.data.local.RankingSubjectDao
import com.songladder.android.data.local.MatchupEventDao
import com.songladder.android.data.local.SongDao
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.data.local.SongLadderJsonPorter
import com.songladder.android.data.local.toEntities
import com.songladder.android.data.local.toPayload
import com.songladder.android.data.local.toSongAndRankingSubjectEntities
import com.songladder.android.data.local.validateForImport
import com.songladder.android.data.local.recomputeDerivedStateWithRepairCount
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.model.TombstoneImportConflict
import com.songladder.android.domain.model.TombstoneImportMatch
import com.songladder.android.domain.model.TombstoneImportAction
import com.songladder.android.domain.model.TombstoneImportResolution
import com.songladder.android.data.local.toMusicSourceType
import com.songladder.android.domain.repository.ImportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class DefaultImportRepository(
    private val database: SongLadderDatabase,
    private val songDao: SongDao,
    private val rankingSubjectDao: RankingSubjectDao,
    private val matchupEventDao: MatchupEventDao,
    private val rankingSettingsDao: RankingSettingsDao,
    private val importBatchDao: ImportBatchDao,
    private val appStatsDao: AppStatsDao,
    private val jsonPorter: SongLadderJsonPorter,
    private val matchupEngine: EloMatchupEngine = EloMatchupEngine()
) : ImportRepository {
    override suspend fun seedSampleSongs(): Result<Int> =
        importTracks(
            sampleSongs.map {
                MusicTrackCandidate(
                    externalId = UUID.randomUUID().toString(),
                    title = it.title,
                    artist = it.artist,
                    album = it.album,
                    artworkUrl = it.artworkUrl,
                    sourceType = MusicSourceType.SAMPLE
                )
            },
            sourceLabel = "Sample Pack"
        )

    override suspend fun importTracks(candidates: List<MusicTrackCandidate>, sourceLabel: String): Result<Int> =
        importTracks(candidates, sourceLabel, emptyMap())

    override suspend fun findTombstoneMatches(
        candidates: List<MusicTrackCandidate>
    ): Result<List<TombstoneImportConflict>> = runCatching {
        database.withTransaction {
            val tombstones = rankingSubjectDao.getAll().filter { it.tombstoneDeletedAt != null }
            val existingKeys = songDao.getSongsWithStats()
                .map { songKey(it.song.title, it.song.artist) }
                .toSet()
            candidates.mapNotNull { candidate ->
                if (songKey(candidate.title, candidate.artist) in existingKeys) return@mapNotNull null
                val matches = tombstones.mapNotNull { subject ->
                    if (matchesTombstone(candidate, subject)) {
                        TombstoneImportMatch(
                            rankingSubjectId = subject.id,
                            title = subject.normalizedTitle,
                            artist = subject.normalizedArtist,
                            sourceType = (subject.tombstoneSourceType ?: subject.sourceType).toMusicSourceType(),
                            externalId = subject.tombstoneExternalId ?: subject.externalId
                        )
                    } else {
                        null
                    }
                }
                matches.takeIf { it.isNotEmpty() }?.let {
                    TombstoneImportConflict(candidate = candidate, matches = it)
                }
            }
        }
    }

    override suspend fun importTracks(
        candidates: List<MusicTrackCandidate>,
        sourceLabel: String,
        resolutions: Map<String, TombstoneImportResolution>
    ): Result<Int> = runCatching {
        database.withTransaction {
            val existingKeys = songDao.getSongsWithStats()
                .map { songKey(it.song.title, it.song.artist) }
                .toMutableSet()
            val tombstones = rankingSubjectDao.getAll().filter { it.tombstoneDeletedAt != null }
            var inserted = 0
            val normalizedCandidates = candidates
                .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
                .distinctBy { songKey(it.title, it.artist) }

            normalizedCandidates.forEach { candidate ->
                val key = songKey(candidate.title, candidate.artist)
                if (existingKeys.contains(key)) return@forEach
                val matches = tombstones.filter { matchesTombstone(candidate, it) }
                val resolution = resolutions[importKey(candidate)]
                val restoredSubject = when {
                    matches.isEmpty() -> null
                    resolution?.action == TombstoneImportAction.RESTORE -> {
                        val selected = matches.singleOrNull { it.id == resolution.rankingSubjectId }
                            ?: if (matches.size == 1 && resolution.rankingSubjectId == null) matches.single() else null
                        selected ?: error("A valid restoration choice is required for ${candidate.title}.")
                    }
                    resolution?.action == TombstoneImportAction.START_FRESH -> {
                        val selected = matches.singleOrNull { it.id == resolution.rankingSubjectId }
                            ?: if (matches.size == 1 && resolution.rankingSubjectId == null) matches.single() else null
                        selected ?: error("A valid start-fresh choice is required for ${candidate.title}.")
                        rankingSubjectDao.update(
                            selected.copy(
                                tombstoneSuppressedExternalId = candidate.externalId,
                                tombstoneSuppressedSourceType = candidate.sourceType.name,
                                tombstoneSuppressedNormalizedTitle = candidate.title.trim().lowercase(),
                                tombstoneSuppressedNormalizedArtist = candidate.artist.trim().lowercase()
                            )
                        )
                        null
                    }
                    else -> error("A restoration choice is required for ${candidate.title}.")
                }
                val input = SongInput(
                    title = candidate.title,
                    artist = candidate.artist,
                    album = candidate.album,
                    artworkUrl = candidate.artworkUrl,
                    sourceType = candidate.sourceType,
                    externalId = candidate.externalId
                )
                val (entity, subject) = input.toSongAndRankingSubjectEntities()
                if (restoredSubject != null) {
                    rankingSubjectDao.update(
                        restoredSubject.copy(
                            sourceType = input.sourceType.name,
                            externalId = input.externalId,
                            normalizedTitle = input.title.trim().lowercase(),
                            normalizedArtist = input.artist.trim().lowercase(),
                            tombstoneDeletedAt = null,
                            tombstoneSourceType = null,
                            tombstoneExternalId = null,
                            tombstoneScoreTenths = null,
                            tombstoneSeedElo = null,
                            tombstoneSuppressedExternalId = null,
                            tombstoneSuppressedSourceType = null,
                            tombstoneSuppressedNormalizedTitle = null,
                            tombstoneSuppressedNormalizedArtist = null
                        )
                    )
                    songDao.insertSong(entity.copy(rankingSubjectId = restoredSubject.id))
                } else {
                    songDao.insertSongWithStats(
                        song = entity,
                        stats = subject
                    )
                }
                existingKeys += key
                inserted += 1
            }

            importBatchDao.insert(
                ImportBatchEntity(
                    id = UUID.randomUUID().toString(),
                    sourceLabel = sourceLabel,
                    importedAt = System.currentTimeMillis(),
                    itemCount = inserted
                )
            )
            inserted
        }
    }

    override suspend fun importFromJson(contentResolver: ContentResolver, uri: Uri): Result<Int> = runCatching {
        val raw = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }
            ?: error("Could not read the selected file.")
        val payload = jsonPorter.decode(raw)
        payload.validateForImport()
        val recomputed = payload.toEntities().recomputeDerivedStateWithRepairCount(matchupEngine)
        val entities = recomputed.entities
        database.withTransaction {
            songDao.clearSongs()
            rankingSubjectDao.clearAll()
            matchupEventDao.clearAll()
            rankingSubjectDao.insertAll(entities.subjects)
            entities.songs.forEach { song ->
                songDao.insertSong(song)
            }
            matchupEventDao.insertAll(entities.events)
            rankingSettingsDao.upsert(entities.settings)
            appStatsDao.upsert(entities.appStats)
        }
        recomputed.repairedSubjectCount
    }

    override suspend fun exportToJson(contentResolver: ContentResolver, uri: Uri): Result<Unit> = runCatching {
        val entities = database.withTransaction {
            ExportEntities(
                songs = songDao.getSongsWithStats().map { it.song },
                subjects = rankingSubjectDao.getAll(),
                events = matchupEventDao.getAll(),
                settings = rankingSettingsDao.get() ?: RankingSettingsEntity(),
                appStats = appStatsDao.getAppStats() ?: AppStatsEntity()
            )
        }
        val payload = entities.toPayload()
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(jsonPorter.encode(payload))
            } ?: error("Could not open the selected export file.")
        }
    }

    private fun songKey(title: String, artist: String): String =
        "${title.trim().lowercase()}::${artist.trim().lowercase()}"

    private fun importKey(candidate: MusicTrackCandidate): String =
        "${candidate.sourceType.name}:${candidate.externalId}:${songKey(candidate.title, candidate.artist)}"

    private fun matchesTombstone(
        candidate: MusicTrackCandidate,
        subject: com.songladder.android.data.local.RankingSubjectEntity
    ): Boolean {
        val sourceType = subject.tombstoneSourceType ?: subject.sourceType
        val externalMatch = candidate.externalId.isNotBlank() &&
            candidate.sourceType.name == sourceType &&
            candidate.externalId == subject.tombstoneExternalId
        val titleArtistMatch = candidate.title.trim().equals(subject.normalizedTitle, ignoreCase = true) &&
            candidate.artist.trim().equals(subject.normalizedArtist, ignoreCase = true)
        val suppressed = (candidate.sourceType.name == subject.tombstoneSuppressedSourceType &&
            candidate.externalId == subject.tombstoneSuppressedExternalId) ||
            (candidate.title.trim().equals(subject.tombstoneSuppressedNormalizedTitle, ignoreCase = true) &&
                candidate.artist.trim().equals(subject.tombstoneSuppressedNormalizedArtist, ignoreCase = true))
        return !suppressed && (externalMatch || titleArtistMatch)
    }
}
