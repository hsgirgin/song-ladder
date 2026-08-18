package com.songladder.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test
    fun openLibraryFromMatchupsDoesNotMakeLibrarySticky() {
        composeRule.setContent {
            SongLadderTheme {
                SongLadderAppContent(
                    matchupsContent = { _, onOpenLibrary ->
                        TestScreen("Matchups destination") {
                            Button(onClick = onOpenLibrary) {
                                Text("Open Library")
                            }
                        }
                    },
                    libraryContent = {
                        TestScreen("Library destination")
                    },
                    rankingsContent = {
                        TestScreen("Rankings destination")
                    },
                    settingsContent = {}
                )
            }
        }

        composeRule.onNodeWithText("Matchups destination").assertIsDisplayed()
        composeRule.onNodeWithText("Open Library").performClick()
        composeRule.onNodeWithText("Library destination").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Matchups").performClick()
        composeRule.onNodeWithText("Matchups destination").assertIsDisplayed()
        composeRule.onNodeWithText("Library destination").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Library").performClick()
        composeRule.onNodeWithText("Library destination").assertIsDisplayed()
    }
}

@Composable
private fun TestScreen(
    label: String,
    content: @Composable () -> Unit = {}
) {
    Column {
        Text(label)
        content()
    }
}
