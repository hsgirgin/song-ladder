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
    fun addSongsSheetOpensFromMatchupsAndDismisses() {
        composeRule.setContent {
            SongLadderTheme {
                SongLadderAppContent(
                    matchupsContent = { _, onAddSongs ->
                        TestScreen("Matchups destination") {
                            Button(onClick = onAddSongs) {
                                Text("Add songs")
                            }
                        }
                    },
                    rankingsContent = { _, _ ->
                        TestScreen("Rankings destination")
                    },
                    settingsContent = {},
                    addSongsContent = { onDismiss ->
                        TestScreen("Add songs destination") {
                            Button(onClick = onDismiss) {
                                Text("Dismiss")
                            }
                        }
                    }
                )
            }
        }

        composeRule.onNodeWithText("Matchups destination").assertIsDisplayed()
        composeRule.onNodeWithText("Add songs").performClick()
        composeRule.onNodeWithText("Add songs destination").assertIsDisplayed()

        composeRule.onNodeWithText("Dismiss").performClick()
        composeRule.onNodeWithText("Add songs destination").assertDoesNotExist()
        composeRule.onNodeWithText("Matchups destination").assertIsDisplayed()
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
