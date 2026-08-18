package com.songladder.android.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.songladder.android.ui.navigation.SongLadderDestination
import com.songladder.android.ui.theme.SongLadderTheme
import org.junit.Rule
import org.junit.Test

class SongLadderAppTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bottomNavigationIsHiddenWhenKeyboardIsVisible() {
        composeRule.setContent {
            SongLadderTheme {
                SongLadderScaffold(
                    currentRoute = SongLadderDestination.Library.route,
                    isImeVisible = true,
                    onDestinationSelected = {}
                ) {
                    Text("Library content")
                }
            }
        }

        composeRule.onNodeWithText("Library content").assertIsDisplayed()
        composeRule.onNodeWithText("Matchups").assertDoesNotExist()
        composeRule.onNodeWithText("Library").assertDoesNotExist()
        composeRule.onNodeWithText("Rankings").assertDoesNotExist()
    }

    @Test
    fun bottomNavigationIsVisibleWhenKeyboardIsHidden() {
        composeRule.setContent {
            SongLadderTheme {
                SongLadderScaffold(
                    currentRoute = SongLadderDestination.Library.route,
                    isImeVisible = false,
                    onDestinationSelected = {}
                ) {
                    Text("Library content")
                }
            }
        }

        composeRule.onNodeWithText("Matchups").assertIsDisplayed()
        composeRule.onNodeWithText("Library").assertIsDisplayed()
        composeRule.onNodeWithText("Rankings").assertIsDisplayed()
    }
}
