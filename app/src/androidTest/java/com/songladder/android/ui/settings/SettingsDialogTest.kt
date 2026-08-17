package com.songladder.android.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.songladder.android.domain.model.DeletedRankingHistory
import com.songladder.android.ui.theme.SongLadderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deletingSelectedRankingHistoryRequiresConfirmation() {
        var deleteCount = 0

        composeRule.setContent {
            SongLadderTheme {
                SettingsDialogContent(
                    uiState = SettingsUiState(
                        deletedHistories = listOf(
                            DeletedRankingHistory(
                                rankingSubjectId = "subject-1",
                                title = "Song",
                                artist = "Artist",
                                scoreTenths = 80,
                                deletedAt = 1L,
                                eventCount = 3
                            )
                        ),
                        selectedHistoryIds = setOf("subject-1")
                    ),
                    onDismiss = {},
                    onAutoPlayChanged = {},
                    onShowTipsAgain = {},
                    onHistorySelectionChanged = {},
                    onClearSelection = {},
                    onDeleteSelected = { deleteCount += 1 }
                )
            }
        }

        composeRule.onNodeWithText("Delete 1 history").performClick()
        composeRule.runOnIdle {
            assertEquals(0, deleteCount)
        }
        composeRule.onNodeWithText("Delete ranking history?").assertIsDisplayed()

        composeRule.onNodeWithText("Delete history").performClick()

        composeRule.runOnIdle {
            assertEquals(1, deleteCount)
        }
    }
}
