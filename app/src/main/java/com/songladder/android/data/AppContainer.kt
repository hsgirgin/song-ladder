package com.songladder.android.data

import android.content.Context
import com.songladder.android.data.itunes.ItunesMusicSourceClient
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.data.local.SongLadderJsonPorter
import com.songladder.android.data.preferences.SessionPreferencesRepository
import com.songladder.android.data.repository.DefaultImportRepository
import com.songladder.android.data.repository.DefaultRankingRepository
import com.songladder.android.data.repository.DefaultSongRepository
import com.songladder.android.data.youtubemusic.YoutubeMusicPlaylistClient
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.repository.ImportRepository
import com.songladder.android.domain.repository.MusicSourceClient
import com.songladder.android.domain.repository.PlaylistSourceClient
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SongRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = SongLadderDatabase.getDatabase(appContext)
    private val matchupEngine = EloMatchupEngine()
    private val jsonPorter = SongLadderJsonPorter()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    val sessionPreferencesRepository = SessionPreferencesRepository(appContext)
    val musicSourceClient: MusicSourceClient = ItunesMusicSourceClient(httpClient)
    val playlistSourceClient: PlaylistSourceClient = YoutubeMusicPlaylistClient(httpClient)
    val songRepository: SongRepository = DefaultSongRepository(
        songDao = database.songDao(),
        appStatsDao = database.appStatsDao()
    )
    val rankingRepository: RankingRepository = DefaultRankingRepository(
        songDao = database.songDao(),
        matchupEngine = matchupEngine,
        appStatsDao = database.appStatsDao()
    )
    val importRepository: ImportRepository = DefaultImportRepository(
        songDao = database.songDao(),
        importBatchDao = database.importBatchDao(),
        appStatsDao = database.appStatsDao(),
        jsonPorter = jsonPorter
    )
}
