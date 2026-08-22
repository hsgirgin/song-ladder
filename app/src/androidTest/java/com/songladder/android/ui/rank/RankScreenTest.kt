package com.songladder.android.ui.rank

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.Suggestion
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
                    reaction = CardReaction.Idle
                )
            }
        }

        composeRule.onNodeWithText("Dreams").assertIsDisplayed()
        composeRule.onNodeWithText("Fleetwood Mac").assertIsDisplayed()
        composeRule.onAllNodesWithText("Choose").assertCountEquals(0)
        composeRule.onAllNodesWithText("Preview unavailable").assertCountEquals(0)
    }

    @Test
    fun compactSuggestionCard_showsOldScoreAlongsideTheNewOneWhenRated() {
        val song = Song(id = "song-1", title = "Dreams", artist = "Fleetwood Mac", createdAt = 1L, scoreTenths = 60)
        val suggestion = Suggestion(
            subjectId = "song-1",
            suggestedScoreTenths = 85,
            comparisonCount = 5,
            scoreGapTenths = 25,
            lastEventSequenceId = 5L
        )

        composeRule.setContent {
            SongLadderTheme {
                CompactSuggestionCard(suggestion = suggestion, song = song, onAccept = {}, onLater = {})
            }
        }

        composeRule.onNodeWithText("6.0").assertIsDisplayed()
        composeRule.onNodeWithText("8.5").assertIsDisplayed()
    }

    @Test
    fun compactSuggestionCard_omitsOldScoreWhenTheSongWasNeverRated() {
        val song = Song(id = "song-1", title = "Dreams", artist = "Fleetwood Mac", createdAt = 1L, scoreTenths = null)
        val suggestion = Suggestion(
            subjectId = "song-1",
            suggestedScoreTenths = 85,
            comparisonCount = 5,
            scoreGapTenths = null,
            lastEventSequenceId = 5L
        )

        composeRule.setContent {
            SongLadderTheme {
                CompactSuggestionCard(suggestion = suggestion, song = song, onAccept = {}, onLater = {})
            }
        }

        composeRule.onNodeWithText("8.5").assertIsDisplayed()
        composeRule.onAllNodesWithText("→").assertCountEquals(0)
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
        composeRule.onAllNodesWithText("Choose the song you prefer").assertCountEquals(0)
        composeRule.onAllNodesWithText("Choose").assertCountEquals(0)
        composeRule.onAllNodesWithText("Skip").assertCountEquals(0)
    }

    @Test
    fun selectingSong_resetsChoosingAnimationForTheNextMatchup() {
        val first = Matchup(
            Song(id = "left", title = "Left song", artist = "Left artist", createdAt = 1L),
            Song(id = "right", title = "Right song", artist = "Right artist", createdAt = 2L)
        )
        val next = Matchup(
            Song(id = "next-left", title = "Next left", artist = "Next artist", createdAt = 3L),
            Song(id = "next-right", title = "Next right", artist = "Next artist", createdAt = 4L)
        )
        var matchup by mutableStateOf(first)

        composeRule.setContent {
            SongLadderTheme {
                RankMatchupContent(
                    uiState = RankUiState(),
                    matchup = matchup,
                    modifier = Modifier.width(360.dp).height(760.dp),
                    onChoose = { _, _ -> matchup = next }
                )
            }
        }

        composeRule.onNodeWithTag("rank_matchup_drag_area").performTouchInput { swipeDown() }
        composeRule.onAllNodesWithText("Choosing").assertCountEquals(0)
    }

    @Test
    fun songCard_clickRequestsPreviewForThatSong() {
        val song = Song(id = "song-1", title = "Dreams", artist = "Fleetwood Mac", createdAt = 1L)
        var previewedSongId: String? = null

        composeRule.setContent {
            SongLadderTheme {
                MinimalSongChoiceCard(
                    song = song,
                    reaction = CardReaction.Idle,
                    onPreview = { previewedSongId = song.id }
                )
            }
        }

        composeRule.onNodeWithText("Dreams").performClick()

        composeRule.runOnIdle {
            assertEquals("song-1", previewedSongId)
        }
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
        composeRule.onAllNodesWithText("Skip").assertCountEquals(0)
    }
}
