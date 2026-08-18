package com.songladder.android.ui.rankings

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.songladder.android.domain.model.RankingPresentation
import com.songladder.android.domain.model.Song
import com.songladder.android.ui.components.SongRatingControl
import com.songladder.android.ui.theme.SongLadderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RankingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listRow_displaysScoreAndExpandedStatisticsAtCompactWidth() {
        composeRule.setContent {
            SongLadderTheme {
                RankingsListRow(
                    rankedSong = RankedSong(
                        rank = 1,
                        song = rankingsSong(
                            scoreTenths = 80,
                            wins = 12,
                            losses = 3,
                            skips = 5
                        )
                    ),
                    previewState = RankingsPreviewState.Unavailable,
                    expanded = true,
                    isSaving = false,
                    onToggleStats = {},
                    onTogglePreview = {},
                    onShowDetails = {},
                    onSaveScore = {},
                    modifier = Modifier.width(280.dp)
                )
            }
        }

        composeRule.onNodeWithText("Score 8.0").assertIsDisplayed()
        composeRule.onNodeWithText("12W 3L · 5 skips").assertIsDisplayed()
        composeRule.onNodeWithText("Preview unavailable").assertIsDisplayed()
    }

    @Test
    fun screenContent_exposesTabsSearchGridModeAndSettingsAction() {
        var openedSettings = false

        composeRule.setContent {
            SongLadderTheme {
                RankingsScreenContent(
                    uiState = RankingsUiState(
                        rankedSongs = listOf(RankedSong(1, rankingsSong(title = "A song"))),
                        selectedTab = RankingsTab.SONGS,
                        presentation = RankingPresentation.GRID
                    ),
                    onTabSelected = {},
                    onSearchActiveChanged = {},
                    onSearchQueryChanged = {},
                    onPresentationChanged = {},
                    onToggleUnrated = {},
                    onToggleStats = {},
                    onTogglePreview = {},
                    onShowDetails = {},
                    onHideDetails = {},
                    onSaveScore = { _, _ -> },
                    onDeleteSong = {},
                    onUndoDelete = {},
                    onOpenSettings = { openedSettings = true }
                )
            }
        }

        composeRule.onNodeWithText("Songs").assertIsDisplayed()
        composeRule.onNodeWithText("Albums").assertIsDisplayed()
        composeRule.onNodeWithText("Artists").assertIsDisplayed()
        composeRule.onNodeWithText("Tap to preview · Hold for details").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").performClick()

        composeRule.runOnIdle {
            assertEquals(true, openedSettings)
        }
    }

    @Test
    fun ratingControlSavesCurrentScore() {
        var saved = false

        composeRule.setContent {
            SongLadderTheme {
                SongRatingControl(
                    scoreTenths = 85,
                    onScoreChange = {},
                    onSave = { saved = true },
                    onCancel = {}
                )
            }
        }

        composeRule.onNodeWithText("8.5").assertIsDisplayed()
        composeRule.onNodeWithText("Save 8.5").performClick()

        composeRule.runOnIdle {
            assertEquals(true, saved)
        }
    }
}

private fun rankingsSong(
    id: String = "song-1",
    title: String = "A song",
    artist: String = "An artist",
    createdAt: Long = 1L,
    scoreTenths: Int? = 80,
    wins: Int = 0,
    losses: Int = 0,
    skips: Int = 0
): Song {
    return Song(
        id = id,
        title = title,
        artist = artist,
        createdAt = createdAt,
        scoreTenths = scoreTenths,
        wins = wins,
        losses = losses,
        skips = skips
    )
}
