package com.songladder.android.data.repository

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.songladder.android.data.local.AppStatsDao
import com.songladder.android.data.local.AppStatsEntity
import com.songladder.android.data.local.ImportBatchDao
import com.songladder.android.data.local.ImportBatchEntity
import com.songladder.android.data.local.RankingStatsEntity
import com.songladder.android.data.local.SongDao
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.data.local.SongLadderJsonPorter
import com.songladder.android.data.local.toEntities
import com.songladder.android.data.local.toDomain
import com.songladder.android.data.local.toSongExport
import com.songladder.android.data.local.toSongEntity
import com.songladder.android.domain.model.ExportPayload
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
                val entity = input.toSongEntity()
                songDao.insertSongWithStats(
                    song = entity,
                    stats = RankingStatsEntity(songId = entity.id)
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
        val (songs, stats) = payload.toEntities()
        database.withTransaction {
            songDao.clearSongs()
            songs.zip(stats).forEach { (song, stat) ->
                songDao.insertSongWithStats(song = song, stats = stat)
            }
            appStatsDao.upsert(
                AppStatsEntity(
                    matchCount = payload.matchCount,
                    skipCount = payload.skipCount,
                    dailyGoalDate = payload.dailyGoalDate,
                    dailyMatchCount = payload.dailyMatchCount
                )
            )
        }
        songs.size
    }

    override suspend fun exportToJson(contentResolver: ContentResolver, uri: Uri): Result<Unit> = runCatching {
        val songs = songDao.getSongsWithStats().map { it.toDomain().toSongExport() }
        val appStats = appStatsDao.getAppStats()
        val payload = ExportPayload(
            songs = songs,
            matchCount = appStats?.matchCount ?: 0,
            skipCount = appStats?.skipCount ?: 0,
            dailyGoalDate = appStats?.dailyGoalDate,
            dailyMatchCount = appStats?.dailyMatchCount ?: 0
        )
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(jsonPorter.encode(payload))
            } ?: error("Could not open the selected export file.")
        }
    }

    private fun songKey(title: String, artist: String): String =
        "${title.trim().lowercase()}::${artist.trim().lowercase()}"
}
