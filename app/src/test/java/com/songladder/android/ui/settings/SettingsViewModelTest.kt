package com.songladder.android.ui.settings

import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.DeletedRankingHistory
import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.RankingHistoryDeletionResult
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.repository.RankingRepository
import com.songladder.android.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `autoplay toggle persists settings`() = runTest {
        val settingsRepository = FakeSettingsRepository()
        val viewModel = SettingsViewModel(
            settingsRepository = settingsRepository,
            rankingRepository = FakeRankingRepository()
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.setAutoPlayMatchupPreviews(false)
        advanceUntilIdle()

        assertEquals(false, settingsRepository.settings.value.autoPlayMatchupPreviews)
    }

    @Test
    fun `deleting selected histories delegates once per selected subject and clears selection`() = runTest {
        val rankingRepository = FakeRankingRepository(
            histories = listOf(
                DeletedRankingHistory("one", "one", "artist", 80, deletedAt = 1L, eventCount = 2),
                DeletedRankingHistory("two", "two", "artist", 70, deletedAt = 2L, eventCount = 3)
            )
        )
        val viewModel = SettingsViewModel(
            settingsRepository = FakeSettingsRepository(),
            rankingRepository = rankingRepository
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.toggleDeletedHistorySelection("one")
        viewModel.toggleDeletedHistorySelection("two")
        viewModel.deleteSelectedRankingHistory()
        advanceUntilIdle()

        assertEquals(listOf("one", "two"), rankingRepository.deletedHistoryIds)
        assertEquals(emptySet<String>(), viewModel.uiState.value.selectedHistoryIds)
        assertTrue(viewModel.uiState.value.status is SettingsStatus.DeletedHistory)
    }
}

private class FakeSettingsRepository(
    initialSettings: RankingSettings = RankingSettings()
) : SettingsRepository {
    val settings = MutableStateFlow(initialSettings)

    override fun observeSettings(): Flow<RankingSettings> = settings

    override suspend fun saveSettings(settings: RankingSettings): Result<Unit> {
        this.settings.value = settings
        return Result.success(Unit)
    }
}

private class FakeRankingRepository(
    histories: List<DeletedRankingHistory> = emptyList()
) : RankingRepository {
    private val historyFlow = MutableStateFlow(histories)
    val deletedHistoryIds = mutableListOf<String>()

    override fun observeStats(): Flow<AppStats> = flowOf(AppStats())

    override fun observeMatchupEvents(): Flow<List<MatchupEvent>> = flowOf(emptyList())

    override fun observeDeletedRankingHistories(): Flow<List<DeletedRankingHistory>> = historyFlow

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> = Result.success(Unit)

    override suspend fun recordSkip(songIds: List<String>): Result<Unit> = Result.success(Unit)

    override suspend fun deleteRankingHistory(rankingSubjectId: String): Result<RankingHistoryDeletionResult> {
        deletedHistoryIds += rankingSubjectId
        return Result.success(RankingHistoryDeletionResult(rankingSubjectId, deletedEventCount = 1))
    }
}
