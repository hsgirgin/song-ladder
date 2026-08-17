package com.songladder.android.data.repository

import com.songladder.android.data.local.RankingSettingsDao
import com.songladder.android.data.local.RankingSettingsEntity
import com.songladder.android.data.local.toDomain
import com.songladder.android.data.local.toEntity
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultSettingsRepository(
    private val settingsDao: RankingSettingsDao
) : SettingsRepository {
    override fun observeSettings(): Flow<RankingSettings> =
        settingsDao.observe().map { (it ?: RankingSettingsEntity()).toDomain() }

    override suspend fun saveSettings(settings: RankingSettings): Result<Unit> = runCatching {
        settingsDao.upsert(settings.toEntity())
    }
}
