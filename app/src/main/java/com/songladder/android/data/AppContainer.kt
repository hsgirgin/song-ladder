package com.songladder.android.data

import android.content.Context
import com.songladder.android.data.connectivity.NetworkAvailabilityMonitor
import com.songladder.android.data.deezer.DeezerSongPreviewResolver
import com.songladder.android.data.itunes.ItunesAlbumMetadataProvider
import com.songladder.android.data.itunes.ItunesMusicSourceClient
import com.songladder.android.data.itunes.ItunesSongPreviewResolver
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.data.local.SongLadderJsonPorter
import com.songladder.android.data.preview.AndroidSongPreviewPlayer
import com.songladder.android.data.preview.FallbackSongPreviewResolver
import com.songladder.android.data.repository.DefaultAlbumRepository
import com.songladder.android.data.repository.DefaultImportRepository
import com.songladder.android.data.repository.DefaultRankingRepository
import com.songladder.android.data.repository.DefaultSettingsRepository
import com.songladder.android.data.repository.DefaultSongRepository
import com.songladder.android.data.youtubemusic.YoutubeMusicPlaylistClient
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.repository.AlbumMetadataProvider
import com.songladder.android.domain.repository.AlbumRepository
import com.songladder.android.domain.repository.ImportRepository
import com.songladder.android.domain.repository.MusicSourceClient
import com.songladder.android.domain.repository.PlaylistSourceClient
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SettingsRepository
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = SongLadderDatabase.getDatabase(appContext)
    private val matchupEngine = EloMatchupEngine()
    private val jsonPorter = SongLadderJsonPorter()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    val musicSourceClient: MusicSourceClient = ItunesMusicSourceClient(httpClient)
    val songPreviewResolver = FallbackSongPreviewResolver(
        listOf(
            ItunesSongPreviewResolver(httpClient),
            DeezerSongPreviewResolver(httpClient)
        )
    )
    val songPreviewPlayer = AndroidSongPreviewPlayer(appContext)
    val playlistSourceClient: PlaylistSourceClient = YoutubeMusicPlaylistClient(httpClient)
    val songRepository: SongRepository = DefaultSongRepository(
        database = database,
        songDao = database.songDao(),
        rankingSubjectDao = database.rankingSubjectDao(),
        matchupEventDao = database.matchupEventDao(),
        appStatsDao = database.appStatsDao(),
        suggestionDismissalDao = database.suggestionDismissalDao()
    )
    val rankingRepository: RankingRepository = DefaultRankingRepository(
        database = database,
        songDao = database.songDao(),
        matchupEngine = matchupEngine,
        rankingSubjectDao = database.rankingSubjectDao(),
        matchupEventDao = database.matchupEventDao(),
        suggestionDismissalDao = database.suggestionDismissalDao(),
        appStatsDao = database.appStatsDao()
    )
    val importRepository: ImportRepository = DefaultImportRepository(
        database = database,
        songDao = database.songDao(),
        rankingSubjectDao = database.rankingSubjectDao(),
        matchupEventDao = database.matchupEventDao(),
        rankingSettingsDao = database.rankingSettingsDao(),
        importBatchDao = database.importBatchDao(),
        appStatsDao = database.appStatsDao(),
        albumDao = database.albumDao(),
        albumTrackExclusionDao = database.albumTrackExclusionDao(),
        albumMissingTrackDao = database.albumMissingTrackDao(),
        suggestionDismissalDao = database.suggestionDismissalDao(),
        jsonPorter = jsonPorter,
        matchupEngine = matchupEngine
    )
    val settingsRepository: SettingsRepository = DefaultSettingsRepository(
        settingsDao = database.rankingSettingsDao()
    )
    private val albumMetadataProvider: AlbumMetadataProvider = ItunesAlbumMetadataProvider(httpClient)
    val albumRepository: AlbumRepository = DefaultAlbumRepository(
        database = database,
        songDao = database.songDao(),
        albumDao = database.albumDao(),
        albumTrackExclusionDao = database.albumTrackExclusionDao(),
        albumMissingTrackDao = database.albumMissingTrackDao(),
        albumMetadataProvider = albumMetadataProvider,
        settingsRepository = settingsRepository
    )
    private val networkAvailabilityMonitor = NetworkAvailabilityMonitor(appContext)

    init {
        networkAvailabilityMonitor.start {
            appScope.launch { albumRepository.retryPendingMatches() }
        }
    }
}
