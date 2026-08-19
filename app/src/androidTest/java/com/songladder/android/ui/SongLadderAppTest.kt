package com.songladder.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
                    currentRoute = SongLadderDestination.Matchups.route,
                    isImeVisible = true,
                    onDestinationSelected = {}
                ) {
                    Text("Matchups content")
                }
            }
        }

        composeRule.onNodeWithText("Matchups content").assertIsDisplayed()
        composeRule.onNodeWithText("Matchups").assertDoesNotExist()
        composeRule.onNodeWithText("Rankings").assertDoesNotExist()
    }

    @Test
    fun bottomNavigationIsVisibleWhenKeyboardIsHidden() {
        composeRule.setContent {
            SongLadderTheme {
                SongLadderScaffold(
                    currentRoute = SongLadderDestination.Matchups.route,
                    isImeVisible = false,
                    onDestinationSelected = {}
                ) {
                    Text("Matchups content")
                }
            }
        }

        composeRule.onNodeWithText("Matchups").assertIsDisplayed()
        composeRule.onNodeWithText("Rankings").assertIsDisplayed()
    }

    @Test
    fun bottomNavigationIsHiddenOnLibraryRoute() {
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

        composeRule.onNodeWithText("Library content").assertIsDisplayed()
        composeRule.onNodeWithText("Matchups").assertDoesNotExist()
        composeRule.onNodeWithText("Rankings").assertDoesNotExist()
    }

    @Test
    fun openLibraryFromMatchupsPushesAndBackReturns() {
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
                    libraryContent = { _, onBack, _ ->
                        TestScreen("Library destination") {
                            Button(onClick = onBack) {
                                Text("Back")
                            }
                        }
                    },
                    rankingsContent = { _, _ ->
                        TestScreen("Rankings destination")
                    },
                    settingsContent = {}
                )
            }
        }

        composeRule.onNodeWithText("Matchups destination").assertIsDisplayed()
        composeRule.onNodeWithText("Open Library").performClick()
        composeRule.onNodeWithText("Library destination").assertIsDisplayed()

        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("Matchups destination").assertIsDisplayed()
        composeRule.onNodeWithText("Library destination").assertDoesNotExist()
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
