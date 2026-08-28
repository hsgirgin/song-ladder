package com.songladder.android.data.repository

import androidx.room.withTransaction
import com.songladder.android.data.local.AppStatsDao
import com.songladder.android.data.local.AppStatsEntity
import com.songladder.android.data.local.MatchupEventDao
import com.songladder.android.data.local.RankingSubjectDao
import com.songladder.android.data.local.SongDao
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.data.local.SuggestionDismissalDao
import com.songladder.android.data.local.toDomain
import com.songladder.android.data.local.toSongAndRankingSubjectEntities
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.model.TimeSource
import com.songladder.android.domain.model.scoreFirstComparator
import com.songladder.android.domain.model.seedEloForScore
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.Normalizer
import java.util.Locale

class DefaultSongRepository(
    private val database: SongLadderDatabase,
    private val songDao: SongDao,
    private val rankingSubjectDao: RankingSubjectDao,
    private val matchupEventDao: MatchupEventDao? = null,
    private val appStatsDao: AppStatsDao? = null,
    private val suggestionDismissalDao: SuggestionDismissalDao? = null,
    private val timeSource: TimeSource = TimeSource { System.currentTimeMillis() }
) : SongRepository {
    override fun observeSongs(): Flow<List<Song>> {
        return songDao.observeSongsWithStats().map { rows ->
            rows.map { it.toDomain() }.sortedWith(scoreFirstComparator())
        }
    }

    override suspend fun addSong(input: SongInput): Result<Unit> = runCatching {
        require(input.title.isNotBlank()) { "Song title is required." }
        require(input.artist.isNotBlank()) { "Artist is required." }
        database.withTransaction {
            val normalizedTitle = input.title.trim()
            val normalizedArtist = input.artist.trim()
            require(
                songDao.getSongsWithStats().none {
                    it.song.title.trim().equals(normalizedTitle, ignoreCase = true) &&
                        it.song.artist.trim().equals(normalizedArtist, ignoreCase = true)
                }
            ) { "A song with this title and artist is already in the library." }
            val (songEntity, subjectEntity) = input.toSongAndRankingSubjectEntities()
            songDao.insertSongWithStats(
                song = songEntity,
                stats = subjectEntity
            )
        }
    }

    override suspend fun removeSong(songId: String): Result<Unit> = runCatching {
        database.withTransaction {
            val song = songDao.getSongWithStats(songId) ?: error("Song not found.")
            val subject = song.stats
            rankingSubjectDao.update(
                subject.copy(
                    tombstoneDeletedAt = timeSource.now(),
                    tombstoneSourceType = subject.sourceType,
                    tombstoneExternalId = subject.externalId,
                    tombstoneScoreTenths = subject.scoreTenths,
                    tombstoneSeedElo = seedEloForScore(subject.scoreTenths),
                    tombstoneSuppressedExternalId = null,
                    tombstoneSuppressedSourceType = null,
                    tombstoneSuppressedNormalizedTitle = null,
                    tombstoneSuppressedNormalizedArtist = null
                )
            )
            songDao.deleteSong(songId)
        }
    }

    override suspend fun restoreSong(input: SongInput, rankingSubjectId: String): Result<Unit> = runCatching {
        require(input.title.isNotBlank()) { "Song title is required." }
        require(input.artist.isNotBlank()) { "Artist is required." }
        database.withTransaction {
            val subject = rankingSubjectDao.get(rankingSubjectId)
                ?: error("Ranking history not found.")
            require(subject.tombstoneDeletedAt != null) { "Ranking history is not deleted." }
            require(
                songDao.getSongsWithStats().none {
                    it.song.title.trim().equals(input.title.trim(), ignoreCase = true) &&
                        it.song.artist.trim().equals(input.artist.trim(), ignoreCase = true)
                }
            ) { "A song with this title and artist is already in the library." }
            val (newSong, _) = input.toSongAndRankingSubjectEntities()
            rankingSubjectDao.update(
                subject.copy(
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
            songDao.insertSong(newSong.copy(rankingSubjectId = subject.id))
        }
    }

    override suspend fun resetLibrary(): Result<Unit> = runCatching {
        database.withTransaction {
            songDao.clearSongs()
            rankingSubjectDao.clearAll()
            matchupEventDao?.clearAll()
            suggestionDismissalDao?.clearAll()
            appStatsDao?.upsert(AppStatsEntity())
        }
    }

    override suspend fun findAmbiguousMatches(candidate: MusicTrackCandidate): Result<List<Song>> = runCatching {
        val exactKey = songKey(candidate.title, candidate.artist)
        songDao.getSongsWithStats()
            .map { it.toDomain() }
            .filter { song -> songKey(song.title, song.artist) != exactKey && isFuzzyMatch(candidate, song) }
    }

    private fun isFuzzyMatch(candidate: MusicTrackCandidate, song: Song): Boolean =
        tokenOverlap(candidate.title, song.title) >= TITLE_OVERLAP_THRESHOLD &&
            tokenOverlap(candidate.artist, song.artist) >= ARTIST_OVERLAP_THRESHOLD

    private fun songKey(title: String, artist: String): String =
        "${title.trim().lowercase()}::${artist.trim().lowercase()}"

    private fun tokenOverlap(a: String, b: String): Double {
        val tokensA = tokens(a)
        val tokensB = tokens(b)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val common = tokensA.count { it in tokensB }
        return common.toDouble() / maxOf(tokensA.size, tokensB.size)
    }

    private fun tokens(value: String): Set<String> =
        normalize(value).split(" ").filterTo(mutableSetOf()) { it.length > 1 }

    private fun normalize(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return ascii
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private companion object {
        const val TITLE_OVERLAP_THRESHOLD = 0.5
        const val ARTIST_OVERLAP_THRESHOLD = 0.6
    }
}
