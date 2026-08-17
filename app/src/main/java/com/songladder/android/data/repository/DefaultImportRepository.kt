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
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.SongInput
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
    private val jsonPorter: SongLadderJsonPorter
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

    override suspend fun importTracks(candidates: List<MusicTrackCandidate>, sourceLabel: String): Result<Int> = runCatching {
        database.withTransaction {
            val existingKeys = songDao.getSongsWithStats()
                .map { songKey(it.song.title, it.song.artist) }
                .toMutableSet()
            var inserted = 0
            val normalizedCandidates = candidates
                .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
                .distinctBy { songKey(it.title, it.artist) }

            normalizedCandidates.forEach { candidate ->
                val key = songKey(candidate.title, candidate.artist)
                if (existingKeys.contains(key)) return@forEach
                val input = SongInput(
                    title = candidate.title,
                    artist = candidate.artist,
                    album = candidate.album,
                    artworkUrl = candidate.artworkUrl,
                    sourceType = candidate.sourceType,
                    externalId = candidate.externalId
                )
                val (entity, subject) = input.toSongAndRankingSubjectEntities()
                songDao.insertSongWithStats(
                    song = entity,
                    stats = subject
                )
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
        val entities = payload.toEntities()
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
        entities.songs.size
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
}
