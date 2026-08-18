package com.songladder.android.ui.rank

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.Song
import com.songladder.android.ui.theme.SongLadderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RankScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rankHeader_describesStatsAsOneAccessibleSummary() {
        val songs = listOf(
            Song(id = "song-1", title = "Dreams", artist = "Fleetwood Mac", createdAt = 1L),
            Song(id = "song-2", title = "Landslide", artist = "Fleetwood Mac", createdAt = 2L)
        )

        composeRule.setContent {
            SongLadderTheme {
                MinimalRankHeader(
                    RankUiState(
                        songs = songs,
                        stats = AppStats(matchCount = 1),
                        streakCount = 3
                    )
                )
            }
        }

        composeRule.onNodeWithText("2 songs · 1 match · 3-choice streak")
            .assertIsDisplayed()
    }

    @Test
    fun songChoiceCard_showsOnlySongIdentity() {
        val song = Song(
            id = "song-1",
            title = "Dreams",
            artist = "Fleetwood Mac",
            createdAt = 1L
        )

        composeRule.setContent {
            SongLadderTheme {
                MinimalSongChoiceCard(
                    song = song,
                    artworkSize = 80.dp,
                    reaction = CardReaction.Idle
                )
            }
        }

        composeRule.onNodeWithText("Dreams").assertIsDisplayed()
        composeRule.onNodeWithText("Fleetwood Mac").assertIsDisplayed()
        composeRule.onNodeWithText("Choose").assertDoesNotExist()
        composeRule.onNodeWithText("Preview unavailable").assertDoesNotExist()
    }

    @Test
    fun matchup_showsOnlyTheTwoSongs() {
        val left = Song(id = "left", title = "Left song", artist = "Left artist", createdAt = 1L)
        val right = Song(id = "right", title = "Right song", artist = "Right artist", createdAt = 2L)

        composeRule.setContent {
            SongLadderTheme {
                RankMatchupContent(
                    uiState = RankUiState(
                        songs = listOf(left, right),
                        previews = mapOf(
                            left.id to SongPreviewState.Unavailable,
                            right.id to SongPreviewState.Unavailable
                        )
                    ),
                    matchup = Matchup(left, right),
                    modifier = Modifier
                        .width(360.dp)
                        .height(760.dp),
                    onChoose = { _, _ -> }
                )
            }
        }

        composeRule.onNodeWithText("Left song").assertIsDisplayed()
        composeRule.onNodeWithText("Right song").assertIsDisplayed()
        composeRule.onNodeWithText("Choose the song you prefer").assertDoesNotExist()
        composeRule.onNodeWithText("Choose").assertDoesNotExist()
        composeRule.onNodeWithText("Skip").assertDoesNotExist()
    }

    @Test
    fun matchup_exposes_accessibility_actions_for_both_choices() {
        val left = Song(id = "left", title = "Left song", artist = "Left artist", createdAt = 1L)
        val right = Song(id = "right", title = "Right song", artist = "Right artist", createdAt = 2L)
        val choices = mutableListOf<Pair<String, String>>()

        composeRule.setContent {
            SongLadderTheme {
                RankMatchupContent(
                    uiState = RankUiState(
                        songs = listOf(left, right),
                        previews = mapOf(
                            left.id to SongPreviewState.Unavailable,
                            right.id to SongPreviewState.Unavailable
                        )
                    ),
                    matchup = Matchup(left, right),
                    modifier = Modifier
                        .width(360.dp)
                        .height(760.dp),
                    onChoose = { winner, loser -> choices += winner to loser }
                )
            }
        }

        val customActions = composeRule
            .onNodeWithTag("rank_matchup_drag_area")
            .fetchSemanticsNode()
            .config[SemanticsProperties.CustomActions]

        assertEquals(2, customActions.size)
        assertEquals("Choose Left song by Left artist", customActions[0].label)
        assertEquals("Choose Right song by Right artist", customActions[1].label)

        composeRule.runOnIdle {
            assertTrue(customActions[0].action?.invoke() == true)
            assertTrue(customActions[1].action?.invoke() == true)
        }

        assertEquals(
            listOf("left" to "right", "right" to "left"),
            choices
        )
    }

    @Test
    fun verticalSwipeUpChoosesBottomSongWhenGesturesAreEnabled() {
        val left = Song(id = "left", title = "Left song", artist = "Left artist", createdAt = 1L)
        val right = Song(id = "right", title = "Right song", artist = "Right artist", createdAt = 2L)
        var chosen: Pair<String, String>? = null

        composeRule.setContent {
            SongLadderTheme {
                RankMatchupContent(
                    uiState = RankUiState(
                        songs = listOf(left, right),
                        previews = mapOf(
                            left.id to SongPreviewState.Unavailable,
                            right.id to SongPreviewState.Unavailable
                        )
                    ),
                    matchup = Matchup(left, right),
                    modifier = Modifier
                        .width(360.dp)
                        .height(760.dp),
                    onChoose = { winner, loser -> chosen = winner to loser }
                )
            }
        }

        composeRule.onNodeWithTag("rank_matchup_drag_area").performTouchInput {
            swipeUp()
        }

        composeRule.runOnIdle {
            assertEquals("right" to "left", chosen)
        }
    }

    @Test
    fun verticalSwipeDownChoosesTopSongWhenGesturesAreEnabled() {
        val left = Song(id = "left", title = "Left song", artist = "Left artist", createdAt = 1L)
        val right = Song(id = "right", title = "Right song", artist = "Right artist", createdAt = 2L)
        var chosen: Pair<String, String>? = null

        composeRule.setContent {
            SongLadderTheme {
                RankMatchupContent(
                    uiState = RankUiState(
                        songs = listOf(left, right),
                        previews = mapOf(
                            left.id to SongPreviewState.Unavailable,
                            right.id to SongPreviewState.Unavailable
                        )
                    ),
                    matchup = Matchup(left, right),
                    modifier = Modifier
                        .width(360.dp)
                        .height(760.dp),
                    onChoose = { winner, loser -> chosen = winner to loser }
                )
            }
        }

        composeRule.onNodeWithTag("rank_matchup_drag_area").performTouchInput {
            swipeDown()
        }

        composeRule.runOnIdle {
            assertEquals("left" to "right", chosen)
        }
    }

    @Test
    fun matchup_keepsBothSongsReachableAtCompactHeight() {
        val left = Song(
            id = "left",
            title = "Left song",
            artist = "Left artist",
            createdAt = 1L
        )
        val right = Song(
            id = "right",
            title = "Right song",
            artist = "Right artist",
            createdAt = 2L
        )

        composeRule.setContent {
            SongLadderTheme {
                RankMatchupContent(
                    uiState = RankUiState(
                        songs = listOf(left, right),
                        previews = mapOf(
                            left.id to SongPreviewState.Unavailable,
                            right.id to SongPreviewState.Unavailable
                        )
                    ),
                    matchup = Matchup(left, right),
                    modifier = Modifier
                        .width(320.dp)
                        .height(320.dp),
                    onChoose = { _, _ -> }
                )
            }
        }

        composeRule.onNodeWithText("Right song")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Left artist")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Right artist")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Skip").assertDoesNotExist()
    }
}
